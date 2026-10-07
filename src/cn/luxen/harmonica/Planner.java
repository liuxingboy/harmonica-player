package cn.luxen.harmonica;

import java.util.*;

/** Pure timing/key mapping, independently testable without Android. */
public final class Planner {
    public static final int[] NATURAL={0,2,4,5,7,9,11,12};
    public static final int CONTROL_MS=30, CONTROL_GAP_MS=15, RELEASE_GAP_MS=24;
    public static final class Key {
        public final int index,octave; public final boolean sharp;
        Key(int i,int o,boolean s) { index=i; octave=o; sharp=s; }
    }
    public static final class Step {
        public int noteNumber, pitch; public long start, onset, hold;
        public Key key; public final List<Integer> controls=new ArrayList<>();
        public long end() { return onset+hold; }
    }
    public static Key key(int pitch,int oldOctave,boolean oldSharp) {
        Key best=null; int bestCost=Integer.MAX_VALUE;
        for(int octave=-1;octave<=1;octave++) for(int i=0;i<8;i++) for(int sharp=0;sharp<=1;sharp++) {
            if(60+12*octave+NATURAL[i]+sharp!=pitch) continue;
            int cost=(octave==oldOctave?0:10)+((sharp==1)==oldSharp?0:10)+sharp;
            if(cost<bestCost) { bestCost=cost; best=new Key(i,octave,sharp==1); }
        }
        if(best==null) throw new IllegalArgumentException("音符 "+MidiFile.name(pitch)+" 超出琴键 C3～C#6，请调整移调");
        return best;
    }
    public static List<Step> build(List<MidiFile.Note> notes,double speed,int transpose) {
        if(notes.isEmpty()) throw new IllegalArgumentException("没有旋律音符");
        if(speed<.25 || speed>2 || transpose < -36 || transpose > 36) throw new IllegalArgumentException("速度或移调超出范围");
        List<Step> steps=new ArrayList<>(); int octave=0; boolean sharp=false; double offset=notes.get(0).start;
        for(int i=0;i<notes.size();i++) {
            MidiFile.Note n=notes.get(i); Step s=new Step(); s.noteNumber=i; s.pitch=n.pitch+transpose;
            s.key=key(s.pitch,octave,sharp);
            if(s.key.octave!=octave) s.controls.add(s.key.octave==-1?11:s.key.octave==0?10:9);
            if(s.key.sharp!=sharp) s.controls.add(8);
            s.onset=800+Math.round((n.start-offset)*1000/speed);
            s.start=s.onset-s.controls.size()*(CONTROL_MS+CONTROL_GAP_MS);
            s.hold=Math.max(1,Math.round(n.duration*1000/speed));
            steps.add(s); octave=s.key.octave; sharp=s.key.sharp;
        }
        for(int i=0;i<steps.size();i++) {
            Step s=steps.get(i);
            if(i+1<steps.size()) s.hold=Math.min(s.hold,steps.get(i+1).start-s.onset-RELEASE_GAP_MS);
            if(s.hold<25) throw new IllegalArgumentException("第 "+(i+1)+" 个音符变调过密，请降低速度或选择更简洁的旋律");
            // Very long notes are held at most 2 s so stopping never waits for a long sustained chord.
            s.hold=Math.min(s.hold,2000);
        }
        return steps;
    }
}
