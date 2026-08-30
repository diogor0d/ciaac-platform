package com.ciaac.minecraft.minigames.anvildodge;

import com.ciaac.minecraft.minigames.hotpotato.OperationId;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/** Paper-neutral, strict-isolation wave state machine. */
public final class AnvilDodgeGame {
    private final UUID matchId; private final AnvilDodgeConfig config; private final List<AnvilWave> waves;
    private final LinkedHashSet<UUID> roster = new LinkedHashSet<>(); private final Set<UUID> live = new HashSet<>();
    private final Map<UUID,Integer> dodges = new HashMap<>(); private final Set<OperationId> operations = new HashSet<>();
    private AnvilDodgePhase phase = AnvilDodgePhase.DISABLED; private int wave; private long sequence; private Instant started; private AnvilDodgeResult result;
    public AnvilDodgeGame(UUID id, AnvilDodgeConfig config) { matchId=Objects.requireNonNull(id); this.config=Objects.requireNonNull(config); waves=plan(config); }
    public synchronized AnvilDodgePhase phase(){return phase;} public synchronized Set<UUID> roster(){return Set.copyOf(roster);}
    public synchronized Set<UUID> livePlayers(){return Set.copyOf(live);}
    public synchronized int wave(){return wave;} public synchronized List<AnvilWave> plan(){return waves;} public synchronized Optional<AnvilDodgeResult> result(){return Optional.ofNullable(result);}
    public synchronized void open(OperationId op){ mutate(op, AnvilDodgePhase.WAITING); }
    public synchronized void join(UUID p, OperationId op){ require(AnvilDodgePhase.WAITING,op); Objects.requireNonNull(p); if(roster.contains(p)||roster.size()>=config.maximumPlayers()) throw new IllegalStateException("roster is full or duplicate"); accept(op); roster.add(p); }
    public synchronized void start(Instant now, OperationId op){ require(AnvilDodgePhase.WAITING,op); if(roster.size()<config.minimumPlayers()) throw new IllegalStateException("minimum players not reached"); accept(op); phase=AnvilDodgePhase.RUNNING; started=Objects.requireNonNull(now); wave=1; live.clear(); live.addAll(roster); }
    public synchronized void dodge(UUID player, OperationId op){ require(AnvilDodgePhase.RUNNING,op); if(!live.contains(player)) throw new IllegalStateException("player is not live"); accept(op); dodges.merge(player,1,Integer::sum); }
    public synchronized void eliminate(UUID player, Instant now, OperationId op){ require(AnvilDodgePhase.RUNNING,op); if(!live.remove(player)) throw new IllegalStateException("player is not live"); accept(op); if(live.isEmpty()) finish(now,"ELIMINATED"); }
    public synchronized void completeWave(OperationId op){ require(AnvilDodgePhase.RUNNING,op); accept(op); if(wave>=config.waves()) finish(started.plus(config.waveDuration().multipliedBy(wave)),"COMPLETED"); else wave++; }
    public synchronized void recover(OperationId op){ if(phase!=AnvilDodgePhase.WAITING&&phase!=AnvilDodgePhase.RUNNING) throw new IllegalStateException("cannot recover from "+phase); accept(op); phase=AnvilDodgePhase.RECOVERING; live.clear(); }
    public synchronized void close(OperationId op){ if(phase!=AnvilDodgePhase.RECOVERING&&phase!=AnvilDodgePhase.FINISHING) throw new IllegalStateException("cannot close"); accept(op); phase=AnvilDodgePhase.CLOSED; }
    private void finish(Instant now,String reason){ phase=AnvilDodgePhase.FINISHING; for(UUID player:roster)dodges.putIfAbsent(player,0); result=new AnvilDodgeResult(matchId,config.rulesetRevision(),reason.equals("COMPLETED"),Math.max(0,wave),Duration.between(started,now),dodges,live,reason); }
    private void mutate(OperationId op,AnvilDodgePhase next){ if(phase!=AnvilDodgePhase.DISABLED) throw new IllegalStateException("open is not legal from "+phase); accept(op); phase=next; }
    private void require(AnvilDodgePhase expected,OperationId op){ if(phase!=expected) throw new IllegalStateException("operation is not legal from "+phase); Objects.requireNonNull(op); if(!matchId.equals(op.matchId())) throw new IllegalArgumentException("wrong match"); if(operations.contains(op)||op.sequence()<=sequence) throw new IllegalStateException("stale or duplicate operation"); }
    private void accept(OperationId op){Objects.requireNonNull(op); if(!matchId.equals(op.matchId())||operations.contains(op)||op.sequence()<=sequence) throw new IllegalStateException("stale or duplicate operation"); operations.add(op); sequence=op.sequence();}
    private static List<AnvilWave> plan(AnvilDodgeConfig c){
        var random=RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(c.seed());
        var result=new ArrayList<AnvilWave>();
        for(int wave=1;wave<=c.waves();wave++){
            long requested=(long)c.hazardsPerWaveStart()+(long)(wave-1)*c.hazardsPerWaveIncrement();
            int count=(int)Math.min(c.floorCells(),Math.max(1L,requested));
            var candidates=new ArrayList<Integer>(c.floorCells());
            for(int cell=0;cell<c.floorCells();cell++)candidates.add(cell);
            for(int index=0;index<count;index++){
                int selected=index+random.nextInt(candidates.size()-index);
                Collections.swap(candidates,index,selected);
            }
            result.add(new AnvilWave(wave,candidates.subList(0,count)));
        }
        return List.copyOf(result);
    }
}
