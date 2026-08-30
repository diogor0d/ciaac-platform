package com.ciaac.minecraft.minigames.checkpointparkour;
import java.time.*; import java.util.*;
/** Ordered-checkpoint domain; the Paper adapter owns spawn-safezone geometry and isolation. */
public final class ParkourSession{
 private final UUID id;private final ParkourConfig config;private final Set<UUID> events=new HashSet<>();private ParkourPhase phase=ParkourPhase.READY;private Instant started;private int next;private Duration penalty=Duration.ZERO;private ParkourResult result;
 public ParkourSession(UUID id,ParkourConfig c){this.id=Objects.requireNonNull(id);config=Objects.requireNonNull(c);} public synchronized ParkourPhase phase(){return phase;}public synchronized Optional<ParkourResult> result(){return Optional.ofNullable(result);}
 public synchronized void start(Instant now,UUID event){event(event);if(phase!=ParkourPhase.READY)throw new IllegalStateException("not ready");started=Objects.requireNonNull(now);phase=ParkourPhase.RUNNING;}
 public synchronized void checkpoint(int index,Instant now,UUID event){event(event);if(phase!=ParkourPhase.RUNNING)throw new IllegalStateException("not running");if(index!=next)throw new IllegalArgumentException("checkpoint must be ordered");if(Objects.requireNonNull(now).isBefore(started))throw new IllegalArgumentException("time before start");next++;if(next==config.checkpointCount()){Duration elapsed=elapsedAt(now);if(expired(now)){invalidate(now,"TIME_LIMIT");}else{phase=ParkourPhase.FINISHED;result=new ParkourResult(UUID.randomUUID(),id,config.rulesetRevision(),elapsed,next,true,"COMPLETED");}}}
 public synchronized void fallOrLeave(Instant now,UUID event){event(event);if(phase!=ParkourPhase.RUNNING)throw new IllegalStateException("not running");penalty=penalty.plus(config.penalty());if(expired(now))invalidate(now,"TIME_LIMIT");}
 public synchronized void tick(Instant now,UUID event){Objects.requireNonNull(now);if(phase!=ParkourPhase.RUNNING)return;if(expired(now)){event(event);invalidate(now,"TIME_LIMIT");}}
 public synchronized void cancel(UUID event){event(event);if(phase==ParkourPhase.CLOSED)throw new IllegalStateException("closed");phase=ParkourPhase.CANCELLED;}
 public synchronized void close(UUID event){event(event);if(phase!=ParkourPhase.FINISHED&&phase!=ParkourPhase.INVALID&&phase!=ParkourPhase.CANCELLED)throw new IllegalStateException("not terminal");phase=ParkourPhase.CLOSED;}
 /** The timeout is inclusive: completion is valid only strictly before the limit. */
 private boolean expired(Instant now){return elapsedAt(now).compareTo(config.maximumDuration())>=0;}private Duration elapsedAt(Instant now){return Duration.between(started,Objects.requireNonNull(now)).plus(penalty);}private void invalidate(Instant now,String reason){phase=ParkourPhase.INVALID;result=new ParkourResult(UUID.randomUUID(),id,config.rulesetRevision(),elapsedAt(now),next,false,reason);}private void event(UUID e){if(!events.add(Objects.requireNonNull(e)))throw new IllegalStateException("duplicate event");}
}
