package cn.luxen.harmonica;
import java.io.*;
import java.util.*;

public final class CoreCheck {
    private static void require(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static byte[] synthetic()throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x4d546864);out.writeInt(6);out.writeShort(1);out.writeShort(2);out.writeShort(480);
        byte[] conductor={0,(byte)255,81,3,7,(byte)161,32,(byte)131,96,(byte)255,81,3,15,66,64,0,(byte)255,47,0};
        byte[] melody={0,(byte)144,60,100,(byte)135,64,(byte)128,60,0,0,(byte)255,47,0};
        out.writeInt(0x4d54726b);out.writeInt(conductor.length);out.write(conductor);
        out.writeInt(0x4d54726b);out.writeInt(melody.length);out.write(melody);out.flush();return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        MidiFile source,asset;
        try(InputStream in=new FileInputStream(args[0])){source=MidiFile.read(in);}
        try(InputStream in=new FileInputStream(args[1])){asset=MidiFile.read(in);}
        List<MidiFile.Note> a=source.melody(8),b=asset.melody(1);
        require(a.size()==139 && b.size()==139,"139 notes expected");
        double offset=a.get(0).start;
        for(int i=0;i<a.size();i++){
            require(a.get(i).pitch==b.get(i).pitch,"pitch "+i);
            require(Math.abs(a.get(i).start-offset-b.get(i).start)<1e-8,"onset "+i);
            require(Math.abs(a.get(i).duration-b.get(i).duration)<1e-8,"duration "+i);
        }
        for(double speed:new double[]{.5,.75,1}){
            List<Planner.Step> plan=Planner.build(b,speed,-2);
            for(int i=0;i<plan.size();i++){
                Planner.Step s=plan.get(i);
                require(s.pitch==60+12*s.key.octave+Planner.NATURAL[s.key.index]+(s.key.sharp?1:0),"mapped pitch "+i);
                require(s.hold>=25,"minimum key hold "+i);
                if(i>0) require(plan.get(i-1).end()+Planner.RELEASE_GAP_MS<=s.start,"overlapping gestures "+i);
            }
            System.out.println("PASS 139 notes and non-overlapping mapped gestures, speed="+speed);
        }
        boolean rejected=false;
        try{Planner.build(b,1,24);}catch(IllegalArgumentException e){rejected=true;}
        require(rejected,"out-of-range notes must fail before playback");
        ByteArrayInputStream malformed=new ByteArrayInputStream(new byte[]{1,2,3,4});rejected=false;
        try{MidiFile.read(malformed);}catch(IOException e){rejected=true;}
        require(rejected,"malformed MIDI must be rejected");
        MidiFile tempoFile=MidiFile.read(new ByteArrayInputStream(synthetic()));
        require(tempoFile.notes.size()==1 && Math.abs(tempoFile.notes.get(0).duration-1.5)<1e-9,"format1 global tempo change");
        System.out.println("PASS format1 conductor tempo change: 960 ticks = 1.5 seconds");
        System.out.println("PASS malformed input and pitch-range guard");
    }
}
