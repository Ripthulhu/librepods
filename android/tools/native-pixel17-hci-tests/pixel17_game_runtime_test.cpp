// SPDX-License-Identifier: GPL-3.0-or-later
#define ELD_HCI_TEST_HELPERS_ONLY
#include "pixel17_hci_test.cpp"
#include "pixel17_game_runtime.h"
#include <functional>
#include <string>
namespace {
eld::Pixel17GameHostState route;
size_t refreshes=0;
std::function<void()> during_refresh;
eld::EncoderClockStatus mock_clock;
uint64_t next_clock=100;
size_t clock_begins=0,clock_cancels=0;
size_t clock_attempts=0;
eld::EncoderClockRejection clock_rejection=eld::EncoderClockRejection::None;
std::function<void()> during_clock_begin;
void init_route() {
    const auto expected=owner();
    std::memcpy(route.peer,expected.peer,6);
    route.media_generation=expected.media_generation;
    route.encoder_generation=expected.encoder_generation;
    route.acl_handle=expected.acl_handle; route.media_cid=0x456;
    route.route_valid=true; route.encoder_ready=true; route.streaming=true;
    route.native_sco=false;
}
Pixel17HciOwner expected() {
    Pixel17HciOwner value;
    std::memcpy(value.peer,route.peer,6);
    value.media_generation=route.media_generation;
    value.encoder_generation=route.encoder_generation;
    value.acl_handle=route.acl_handle;
    return value;
}
}
namespace eld {
EncoderClockStatus encoder_clock_status(bool) {
    // Reentrant pure snapshot proves callers never retain RuntimeMutex while
    // entering the real encoder's independent mutex.
    (void)pixel17_game_status();
    return mock_clock;
}
uint64_t encoder_clock_begin(const uint8_t* peer,size_t size,uint64_t generation,
                             uint32_t interval,uint32_t drift) {
    (void)pixel17_game_status(); ++clock_begins;
    assert(peer && size==6 && generation==route.encoder_generation && !std::memcmp(peer,route.peer,6));
    mock_clock={}; mock_clock.serial=++next_clock; mock_clock.encoder_generation=generation;
    std::memcpy(mock_clock.peer,peer,6); mock_clock.phase=EncoderClockPhase::Armed;
    mock_clock.requested_interval_tenths=interval; mock_clock.effective_interval_tenths=10;
    mock_clock.target_drift_ms=drift; mock_clock.started_us=clock_us;
    mock_clock.deadline_us=clock_us+1000000;
    if(during_clock_begin) during_clock_begin();
    return mock_clock.serial;
}
uint64_t encoder_clock_begin_checked(const uint8_t* peer,size_t size,uint64_t generation,
                                     uint32_t interval,uint32_t drift,EncoderClockAdmission* result) {
    ++clock_attempts;
    if(result) { *result={}; result->reason=clock_rejection; }
    return clock_rejection==EncoderClockRejection::None ? encoder_clock_begin(peer,size,generation,interval,drift) : 0;
}
bool encoder_clock_cancel(uint64_t serial) {
    (void)pixel17_game_status(); ++clock_cancels;
    if(serial!=mock_clock.serial || (mock_clock.phase!=EncoderClockPhase::Armed &&
        mock_clock.phase!=EncoderClockPhase::Active)) return false;
    mock_clock.phase=EncoderClockPhase::Cancelled; mock_clock.effective_interval_tenths=10; return true;
}
bool pixel17_game_host_is_event_thread() noexcept { return Radio::self && Radio::self->is_main; }
bool pixel17_game_host_refresh_on_event_thread() {
    assert(pixel17_game_host_is_event_thread()); ++refreshes;
    if(during_refresh) during_refresh();
    return true;
}
Pixel17GameHostState pixel17_game_host_snapshot() { return route; }
void pixel17_game_runtime_tick_for_test();
void pixel17_game_runtime_monitor_for_test();
}
int main(int argc,char** argv) {
    assert(argc==2);
    dispatcher_tests(); decoder_tests(); hci_tests();
    Loop loop; Radio radio; init_route();
    const auto bindings=Pixel17MainBindings{&loop,Loop::construct,Loop::post,Loop::destroy,now,nullptr};
    assert(pixel17_game_runtime_prepare(bindings,radio.bindings()));
    assert(!pixel17_game_runtime_prepare(bindings,radio.bindings()));
    const std::string scenario=argv[1];
    const auto submit=[&](Pixel17GameOperation op) {
        return pixel17_game_submit(op,42,expected(),(clock_us+5000000)*1000);
    };
    assert(pixel17_game_status().supported);
    assert(pixel17_game_refresh()); loop.run(); assert(refreshes==1);
    if(scenario.rfind("flush",0)==0) {
        const uint16_t original=scenario=="flush320" ? 320 : 0;
#if ELD_KEEP_GAME_FLUSH_BASELINE
        const uint16_t target=original;
#else
        const uint16_t target=96;
#endif
        const auto serial=submit(Pixel17GameOperation::Prepare); assert(serial); loop.run();
        assert(!pixel17_game_status().baseline_known && radio.transmissions==1 && radio.command[8]==0x27);
        if(scenario=="flush-unknown") {
            radio.reply({0x0e,4,1,0x27,0x0c,0x0c}); loop.run();
            const auto failed=pixel17_game_status();
            assert(!failed.baseline_known && !failed.holding && !failed.restore_pending && !failed.busy);
            assert(failed.phase==Pixel17GamePhase::Failed && radio.transmissions==1); return 0;
        }
        if(scenario=="flush-cancel-before-read") pixel17_game_cancel_owner(42);
        radio.reply(read_event(original)); loop.run();
        if(scenario=="flush-cancel-before-read") {
            const auto cancelled=pixel17_game_status();
            assert(!cancelled.holding && !cancelled.busy && !cancelled.restore_pending && radio.transmissions==1);
            return 0;
        }
        assert(radio.transmissions==2 && radio.command[8]==0x28);
        assert(radio.command[13]==uint8_t(target) && radio.command[14]==uint8_t(target>>8));
        radio.reply(write_event()); loop.run();
        assert(radio.transmissions==3 && radio.command[8]==0x27);
        if(scenario=="flush-stale") ++route.media_generation;
        radio.reply(read_event(scenario=="flush-misread" ? target+1 : target)); loop.run();
        if(scenario=="flush-stale") {
            pixel17_game_runtime_tick_for_test(); loop.run();
            const auto failed=pixel17_game_status();
            assert(!failed.holding && !failed.busy && failed.restore_pending && radio.transmissions==3);
            assert(failed.phase==Pixel17GamePhase::Quarantined); return 0;
        }
        if(scenario!="flush-misread") {
            const auto held=pixel17_game_status();
            assert(held.holding && !held.busy && held.baseline_known && held.current_known);
            assert(held.baseline_slots==original && held.current_slots==target);
            assert(held.restore_pending==(original!=target));
            assert(!pixel17_game_heartbeat(43,serial) && !pixel17_game_restore(42,serial+1));
            assert(pixel17_game_status().holding);
            if(scenario=="flush-expiry") {
                clock_us+=3000001; pixel17_game_runtime_monitor_for_test();
            } else assert(pixel17_game_restore(42,serial));
            pixel17_game_runtime_tick_for_test(); loop.run();
        }
        if(pixel17_game_status().restore_pending) {
            assert(radio.command[8]==0x28 && radio.command[13]==uint8_t(original) && radio.command[14]==uint8_t(original>>8));
            radio.reply(write_event()); loop.run(); radio.reply(read_event(original)); loop.run();
        }
        const auto restored=pixel17_game_status();
        assert(!restored.holding && !restored.busy && !restored.restore_pending);
        assert(restored.current_known && restored.current_slots==original && restored.baseline_slots==original);
        assert(restored.phase==((scenario=="flush-misread" || scenario=="flush-expiry") ? Pixel17GamePhase::Failed : Pixel17GamePhase::Complete));
        assert(radio.transmissions==((original!=target || scenario=="flush-misread") ? 5U : 3U));
        return 0;
    }
    if(scenario=="watchdog-start") {
        assert(submit(Pixel17GameOperation::Roundtrip));
        clock_us+=6000000; pixel17_game_runtime_monitor_for_test();
        const auto status=pixel17_game_status();
        assert(!status.busy && !status.restore_pending && status.phase==Pixel17GamePhase::Failed);
        assert(status.error==Pixel17HciStatus::Timeout && !radio.transmissions);
        loop.run(); assert(!radio.transmissions); return 0;
    }
    if(scenario=="expired-dispatch") {
        assert(submit(Pixel17GameOperation::Roundtrip));
        clock_us+=5000001; loop.run();
        const auto status=pixel17_game_status();
        assert(!status.busy && !status.restore_pending && status.phase==Pixel17GamePhase::Failed);
        assert(!radio.transmissions); return 0;
    }
    if(scenario=="read") {
        assert(submit(Pixel17GameOperation::Read)); loop.run(); radio.reply(read_event(0)); loop.run();
        const auto status=pixel17_game_status();
        assert(status.phase==Pixel17GamePhase::Complete && !status.busy && !status.restore_pending);
        assert(status.baseline_known && status.baseline_slots==0 && radio.transmissions==1); return 0;
    }
    if(scenario=="allocation") {
        radio.allocation_fails=true;
        assert(submit(Pixel17GameOperation::Roundtrip)); loop.run();
        const auto status=pixel17_game_status();
        assert(status.phase==Pixel17GamePhase::Failed && !status.busy && !status.restore_pending);
        assert(!radio.transmissions); return 0;
    }
    if(scenario.rfind("hold",0)==0 || scenario.rfind("clock",0)==0) {
        const uint16_t original=scenario=="hold320" ? 320 : scenario.rfind("hold96",0)==0 ? 96 : 0;
        const auto held=submit(Pixel17GameOperation::Prepare); assert(held); loop.run();
        radio.reply(read_event(original)); loop.run(); radio.reply(write_event()); loop.run();
        radio.reply(read_event(96)); loop.run();
        auto status=pixel17_game_status();
        assert(status.holding && !status.busy && status.restore_pending==(original!=96) && status.phase==Pixel17GamePhase::Holding);
        assert(status.baseline_slots==original && status.current_slots==96 && radio.transmissions==3);
        if(scenario=="hold96" || scenario=="hold96-expiry") {
            assert(pixel17_game_heartbeat(42,held));
            if(scenario=="hold96") assert(pixel17_game_restore(42,held));
            else { clock_us+=3000001; pixel17_game_runtime_monitor_for_test(); }
            loop.run(); status=pixel17_game_status();
            assert(!status.holding && !status.busy && !status.restore_pending);
            assert(status.baseline_slots==96 && status.current_slots==96 && radio.transmissions==3);
            assert(status.phase==(scenario=="hold96" ? Pixel17GamePhase::Complete : Pixel17GamePhase::Failed));
            return 0;
        }
        const auto finish_restore=[&](Pixel17HciStatus error,bool quarantined=false) {
            pixel17_game_runtime_tick_for_test(); loop.run();
            if(quarantined) {
                const auto failed=pixel17_game_status();
                assert(!failed.holding && !failed.busy && failed.restore_pending && failed.phase==Pixel17GamePhase::Quarantined);
                assert(radio.transmissions==3); return;
            }
            assert(radio.command[8]==0x28 && radio.command[13]==uint8_t(original));
            assert(radio.command[14]==uint8_t(original>>8));
            radio.reply(write_event()); loop.run(); radio.reply(read_event(original)); loop.run();
            const auto restored=pixel17_game_status();
            assert(!restored.holding && !restored.busy && !restored.restore_pending && restored.error==error);
        };
        if(scenario=="hold-graceful-refresh") {
            bool restored=false;
            during_refresh=[&] {
                if(!restored) { restored=true; assert(pixel17_game_restore(42,held)); }
            };
            pixel17_game_runtime_tick_for_test(); loop.run(); during_refresh={};
            assert(restored && pixel17_game_status().error==Pixel17HciStatus::Ok);
            finish_restore(Pixel17HciStatus::Ok);
            assert(pixel17_game_status().phase==Pixel17GamePhase::Complete); return 0;
        }
        if(scenario=="hold0" || scenario=="hold320") {
            const auto deadline=status.lease_deadline_ns;
            assert(!pixel17_game_heartbeat(43,held) && !pixel17_game_heartbeat(42,held+1));
            assert(!pixel17_game_restore(43,held) && !pixel17_game_restore(42,held+1));
            assert(pixel17_game_status().lease_deadline_ns==deadline);
            clock_us+=2500000; assert(pixel17_game_heartbeat(42,held));
            clock_us+=2500000; assert(pixel17_game_heartbeat(42,held));
            pixel17_game_runtime_tick_for_test(); loop.run(); assert(pixel17_game_status().holding);
            // The initial preparation deadline is now past; the renewed HOLD lease owns admission.
            clock_us+=100000; assert(pixel17_game_restore(42,held));
            finish_restore(Pixel17HciStatus::Ok);
            assert(pixel17_game_status().phase==Pixel17GamePhase::Complete); return 0;
        }
        if(scenario=="hold-expiry") {
            clock_us+=3000001; pixel17_game_runtime_monitor_for_test(); loop.run();
            assert(!pixel17_game_heartbeat(42,held));
            finish_restore(Pixel17HciStatus::Timeout); return 0;
        }
        if(scenario=="hold-binder") {
            pixel17_game_cancel_owner(43); assert(pixel17_game_status().holding);
            pixel17_game_cancel_owner(42); finish_restore(Pixel17HciStatus::Cancelled); return 0;
        }
        if(scenario=="hold-stalled") {
            pixel17_game_runtime_tick_for_test(); clock_us+=1100000;
            assert(!pixel17_game_heartbeat(42,held));
            assert(!pixel17_game_status().holding);
            loop.run(); finish_restore(Pixel17HciStatus::DispatchFailed); return 0;
        }
        if(scenario=="hold-query-no-renew") {
            const auto deadline=status.lease_deadline_ns;
            clock_us+=2000000; assert(pixel17_game_refresh()); loop.run();
            assert(pixel17_game_status().lease_deadline_ns==deadline);
            clock_us+=1000001; pixel17_game_runtime_monitor_for_test(); loop.run();
            finish_restore(Pixel17HciStatus::Timeout); return 0;
        }
        auto wrong=expected(); ++wrong.encoder_generation;
        assert(!pixel17_game_clock_begin(43,held,expected(),9,100));
        assert(!pixel17_game_clock_begin(42,held+1,expected(),9,100));
        assert(!pixel17_game_clock_begin(42,held,wrong,9,100));
        assert(!pixel17_game_clock_begin(42,held,expected(),10,100));
        assert(!pixel17_game_clock_begin(42,held,expected(),9,501));
        if(scenario.rfind("clock-retry",0)==0) clock_rejection=EncoderClockRejection::DueDebt;
        if(scenario=="clock-static-refusal") clock_rejection=EncoderClockRejection::Priming;
        const auto request=pixel17_game_clock_begin(42,held,expected(),9,100);
        assert(request && pixel17_game_status().clock_pending && !clock_begins);
        if(scenario.rfind("clock-retry",0)==0 || scenario=="clock-static-refusal") {
            loop.run(); assert(!clock_begins && clock_attempts==1);
            if(scenario=="clock-static-refusal") {
                assert(!pixel17_game_status().clock_pending);
                pixel17_game_clock_opportunity(); loop.run(); assert(clock_attempts==1);
            } else {
                assert(pixel17_game_status().clock_pending && !pixel17_game_status().clock.serial);
                pixel17_game_runtime_monitor_for_test(); loop.run(); assert(clock_attempts==1); // Polling is not progress.
                if(scenario=="clock-retry-expiry") {
                    clock_us+=1100000; pixel17_game_runtime_monitor_for_test(); loop.run();
                    assert(!pixel17_game_status().clock_pending);
                    pixel17_game_clock_opportunity(); loop.run(); assert(clock_attempts==1);
                } else if(scenario=="clock-retry-owner") {
                    ++route.encoder_generation;
                    pixel17_game_clock_opportunity(); loop.run();
                    assert(!clock_begins && clock_attempts==1 && !pixel17_game_status().clock_pending);
                } else {
                    for(unsigned i=0;i<20;++i) pixel17_game_clock_opportunity();
                    assert(loop.tasks.size()==1);
                    if(scenario=="clock-retry-cancel") assert(pixel17_game_clock_cancel(42,held));
                    else if(scenario=="clock-retry-success") clock_rejection=EncoderClockRejection::None;
                    loop.run();
                    if(scenario=="clock-retry-success") {
                        assert(clock_begins==1 && clock_attempts==2 && !pixel17_game_status().clock_pending);
                        assert(pixel17_game_status().clock_request_serial==request && pixel17_game_status().clock.serial);
                        pixel17_game_clock_cancel(42,held);
                    } else if(scenario=="clock-retry-cancel") assert(!clock_begins && clock_attempts==1);
                    else {
                        assert(!clock_begins && clock_attempts==2 && pixel17_game_status().clock_pending);
                        pixel17_game_clock_cancel(42,held);
                    }
                }
            }
            assert(pixel17_game_restore(42,held)); finish_restore(Pixel17HciStatus::Ok); return 0;
        }
        if(scenario=="hold96-restore-inflight") {
            during_clock_begin=[&] {
                assert(pixel17_game_restore(42,held));
                const auto pending=pixel17_game_status();
                assert(!pending.holding && !pending.restore_pending && pending.clock_pending);
                assert(!submit(Pixel17GameOperation::Read));
            };
            loop.run(); during_clock_begin={};
            status=pixel17_game_status();
            assert(!status.holding && !status.restore_pending && !status.clock_pending && status.clock.effective_interval_tenths==10);
            assert(status.phase==Pixel17GamePhase::Complete && status.error==Pixel17HciStatus::Ok);
            assert(radio.transmissions==3 && clock_cancels==1); return 0;
        }
        if(scenario=="clock-cancel-pending") {
            assert(!pixel17_game_clock_cancel(43,held) && pixel17_game_status().clock_pending);
            assert(pixel17_game_clock_cancel(42,held)); loop.run();
            assert(!clock_begins && !pixel17_game_status().clock_pending);
            assert(pixel17_game_restore(42,held)); finish_restore(Pixel17HciStatus::Ok); return 0;
        }
        if(scenario=="clock-invalidated-begin") {
            during_clock_begin=[&] {
                const auto previous=route; route.streaming=false;
                pixel17_game_invalidated(Pixel17GameInvalidation::Stop,previous);
            };
            loop.run(); during_clock_begin={};
            assert(clock_begins==1 && clock_cancels==1 && mock_clock.effective_interval_tenths==10);
            finish_restore(Pixel17HciStatus::StaleOwner); return 0;
        }
        if(scenario=="clock-cancel-inflight") {
            during_clock_begin=[&] {
                assert(pixel17_game_clock_cancel(42,held));
                // A mic may not trust nominal10 until this begin returns and its
                // newly allocated encoder serial has actually been cancelled.
                assert(pixel17_game_status().clock_pending);
            };
            loop.run(); during_clock_begin={};
            assert(clock_begins==1 && clock_cancels==1 && !pixel17_game_status().clock_pending);
            assert(mock_clock.effective_interval_tenths==10);
            assert(pixel17_game_restore(42,held)); finish_restore(Pixel17HciStatus::Ok); return 0;
        }
        loop.run(); status=pixel17_game_status();
        assert(!status.clock_pending && status.clock_request_serial==request && status.clock.serial==mock_clock.serial);
        assert(status.clock.encoder_generation==route.encoder_generation && clock_begins==1);
        mock_clock.phase=EncoderClockPhase::Active; mock_clock.effective_interval_tenths=9;
        if(scenario=="clock-wrong-thread") {
            radio.is_main=false; pixel17_game_runtime_tick_for_test(); loop.run(); radio.is_main=true;
            assert(!pixel17_game_status().holding && mock_clock.effective_interval_tenths==10 && clock_cancels==1);
            finish_restore(Pixel17HciStatus::DispatchFailed); return 0;
        }
        if(scenario=="clock-stop" || scenario=="clock-sco" || scenario=="clock-wrong-channel") {
            const auto previous=route; ++route.encoder_generation; route.encoder_ready=false; route.route_valid=false;
            if(scenario=="clock-wrong-channel") ++route.media_cid;
            if(scenario=="clock-sco") route.native_sco=true;
            pixel17_game_invalidated(scenario=="clock-sco" ? Pixel17GameInvalidation::Sco : Pixel17GameInvalidation::Stop,previous);
            assert(mock_clock.effective_interval_tenths==10 && clock_cancels==1); // Before dispatching main.
            finish_restore(Pixel17HciStatus::StaleOwner,scenario=="clock-wrong-channel"); return 0;
        }
        if(scenario=="clock-expiry") {
            clock_us+=3000001; pixel17_game_runtime_monitor_for_test();
            assert(mock_clock.effective_interval_tenths==10 && clock_cancels==1);
            loop.run(); finish_restore(Pixel17HciStatus::Timeout); return 0;
        }
        if(scenario=="clock-new-token") {
            mock_clock.phase=EncoderClockPhase::Completed; mock_clock.effective_interval_tenths=10;
            pixel17_game_runtime_monitor_for_test(); loop.run();
            const auto next=pixel17_game_clock_begin(42,held,expected(),11,50); assert(next && next!=request);
            pixel17_game_runtime_monitor_for_test();
            assert(pixel17_game_status().clock_pending && !pixel17_game_status().clock.serial);
            loop.run(); assert(pixel17_game_status().clock.serial!=status.clock.serial);
        }
        assert(!pixel17_game_clock_cancel(43,held));
        assert(pixel17_game_clock_cancel(42,held) && mock_clock.effective_interval_tenths==10);
        assert(pixel17_game_restore(42,held)); finish_restore(Pixel17HciStatus::Ok); return 0;
    }
    const uint16_t baseline=scenario=="baseline320" ? 320 : 0;
    assert(submit(Pixel17GameOperation::Roundtrip));
    assert(!submit(Pixel17GameOperation::Roundtrip)); loop.run();
    if(scenario=="cancel-at-write") {
        // This refresh is the normal target96 admission, after the baseline READ
        // completes. It must not inherit the relaxed restoration-only gate.
        const auto invalidate_at=refreshes+2;
        during_refresh=[&] {
            if(refreshes==invalidate_at) {
                const auto previous=route; ++route.encoder_generation;
                route.encoder_ready=false; route.route_valid=false; route.native_sco=true;
                pixel17_game_invalidated(Pixel17GameInvalidation::EncoderChanged,previous);
            }
        };
        radio.reply(read_event(baseline)); loop.run(); during_refresh={};
        assert(radio.transmissions==1); // No experiment96 command was submitted.
        pixel17_game_runtime_tick_for_test(); loop.run();
        assert(radio.transmissions==2 && radio.command[8]==0x28 && radio.command[13]==baseline);
        radio.reply(write_event()); loop.run(); radio.reply(read_event(baseline)); loop.run();
        const auto status=pixel17_game_status();
        assert(!status.busy && !status.restore_pending && status.phase==Pixel17GamePhase::Failed); return 0;
    }
    radio.reply(read_event(baseline)); loop.run();
    assert(radio.command[8]==0x28 && radio.command[13]==96 && radio.transmissions==2);
    if(scenario=="late-target") {
        clock_us+=1100000; pixel17_game_runtime_tick_for_test(); loop.run();
        assert(pixel17_game_status().restore_pending);
        pixel17_game_runtime_monitor_for_test();
        clock_us+=2100000; pixel17_game_runtime_monitor_for_test();
        assert(!pixel17_game_status().busy && pixel17_game_status().restore_pending);
        loop.run();
        radio.reply(write_event()); loop.run();
        assert(pixel17_game_status().busy);
        pixel17_game_runtime_tick_for_test(); loop.run();
        assert(radio.command[8]==0x28 && radio.command[13]==baseline && radio.transmissions==3);
        radio.reply(write_event()); loop.run(); radio.reply(read_event(baseline)); loop.run();
        const auto status=pixel17_game_status();
        assert(!status.busy && !status.restore_pending && status.error==Pixel17HciStatus::Timeout); return 0;
    }
    if(scenario=="expired-tick") {
        pixel17_game_runtime_tick_for_test(); clock_us+=2000000; loop.run();
        assert(pixel17_game_status().restore_pending);
        radio.reply(write_event()); loop.run();
        assert(radio.command[8]==0x28 && radio.command[13]==baseline && radio.transmissions==3);
        radio.reply(write_event()); loop.run(); radio.reply(read_event(baseline)); loop.run();
        const auto status=pixel17_game_status();
        assert(!status.busy && !status.restore_pending && status.phase==Pixel17GamePhase::Failed); return 0;
    }
    if(scenario=="pause-restore" || scenario=="wrong-cid" || scenario=="new-media") {
        const auto previous=route; ++route.encoder_generation;
        route.encoder_ready=false; route.route_valid=false; route.native_sco=true;
        if(scenario=="wrong-cid") ++route.media_cid;
        if(scenario=="new-media") ++route.media_generation;
        pixel17_game_invalidated(Pixel17GameInvalidation::Stop,previous);
        pixel17_game_runtime_tick_for_test(); loop.run();
        // Targetwrite still belongs to native. Cancel doesn't free its context.
        assert(radio.transmissions==2);
        radio.reply(write_event()); loop.run();
        pixel17_game_runtime_tick_for_test(); loop.run();
        if(scenario!="pause-restore") {
            const auto status=pixel17_game_status();
            assert(status.restore_pending && !status.busy && status.phase==Pixel17GamePhase::Quarantined);
            assert(radio.transmissions==2);
            pixel17_game_runtime_tick_for_test(); loop.run(); assert(radio.transmissions==2);
            assert(!submit(Pixel17GameOperation::Read)); return 0;
        }
        assert(radio.transmissions==3 && radio.command[8]==0x28 && radio.command[13]==baseline);
        radio.reply(write_event()); loop.run(); radio.reply(read_event(baseline)); loop.run();
        const auto status=pixel17_game_status();
        assert(!status.restore_pending && !status.busy && status.phase==Pixel17GamePhase::Failed); return 0;
    }
    radio.reply(write_event()); loop.run();
    assert(radio.command[8]==0x27 && radio.transmissions==3);
    if(scenario=="restore-allocation") radio.allocation_fails=true;
    radio.reply(read_event(96)); loop.run();
    if(scenario=="restore-allocation") {
        const auto status=pixel17_game_status();
        assert(!status.busy && status.restore_pending && status.phase==Pixel17GamePhase::Quarantined);
        assert(radio.transmissions==3);
        pixel17_game_runtime_tick_for_test(); loop.run(); assert(radio.transmissions==3);
        assert(!submit(Pixel17GameOperation::Read)); return 0;
    }
    assert(radio.command[8]==0x28 && radio.command[13]==uint8_t(baseline) && radio.command[14]==uint8_t(baseline>>8));
    if(scenario=="late-restore") {
        const auto before=radio.transmissions;
        clock_us+=1100000; pixel17_game_runtime_tick_for_test(); loop.run();
        pixel17_game_runtime_monitor_for_test(); clock_us+=2100000;
        pixel17_game_runtime_monitor_for_test();
        assert(!pixel17_game_status().busy && pixel17_game_status().restore_pending); loop.run();
        radio.reply(write_event()); loop.run();
        pixel17_game_runtime_tick_for_test(); loop.run();
        // A late cleanup ACK schedules READ verification, never another WRITE.
        assert(radio.transmissions==before+1 && radio.command[8]==0x27);
        radio.reply(read_event(baseline)); loop.run();
        const auto status=pixel17_game_status();
        assert(!status.busy && !status.restore_pending && status.error==Pixel17HciStatus::Timeout); return 0;
    }
    radio.reply(write_event()); loop.run();
    assert(radio.command[8]==0x27 && radio.transmissions==5);
    radio.reply(read_event(baseline)); loop.run();
    const auto status=pixel17_game_status();
    assert(status.phase==Pixel17GamePhase::Complete && !status.busy && !status.restore_pending);
    assert(status.baseline_known && status.current_known && status.baseline_slots==baseline && status.current_slots==baseline);
}
