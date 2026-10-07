package cn.luxen.harmonica;

import java.io.*;
import java.util.*;

/** Bounded SMF 0/1 reader. Notes retain note-off duration and global tempo changes. */
public final class MidiFile {
    public static final class Note {
        public long startTick, endTick;
        public int pitch, velocity, channel;
        public double start, duration;
        Note(long a, long b, int p, int v, int c) { startTick=a; endTick=b; pitch=p; velocity=v; channel=c; }
    }
    public final List<Note> notes = new ArrayList<>();
    public final TreeMap<Long,Integer> tempos = new TreeMap<>();
    public int division;
    private static int vlq(DataInputStream in) throws IOException {
        int n=0;
        for (int i=0; i<4; i++) { int b=in.readUnsignedByte(); n=(n<<7)|(b&127); if(b<128) return n; }
        throw new IOException("MIDI 长度编码错误");
    }
    public static MidiFile read(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);
        if(in.readInt()!=0x4d546864) throw new IOException("文件不是标准 MIDI");
        int header=in.readInt();
        if(header<6 || header>1024) throw new IOException("MIDI 头长度错误");
        int format=in.readUnsignedShort(), count=in.readUnsignedShort();
        MidiFile file=new MidiFile(); file.division=in.readUnsignedShort();
        if(format>1 || (file.division&32768)!=0 || file.division==0 || count<1 || count>256)
            throw new IOException("只支持 MIDI 0/1 和节拍时间制");
        in.skipBytes(header-6); file.tempos.put(0L,500000);
        int total=0;
        for(int track=0; track<count; track++) {
            if(in.readInt()!=0x4d54726b) throw new IOException("MIDI 音轨错误");
            int size=in.readInt(); total+=size;
            if(size<0 || size>5_000_000 || total>5_000_000) throw new IOException("MIDI 最大支持 5 MB");
            byte[] bytes=new byte[size]; in.readFully(bytes);
            DataInputStream data=new DataInputStream(new ByteArrayInputStream(bytes));
            Map<Integer,ArrayDeque<long[]>> active=new HashMap<>(); long tick=0; int running=-1;
            while(data.available()>0) {
                tick+=vlq(data); int status=data.readUnsignedByte(); int first=-1;
                if(status<128) { first=status; status=running; if(status<0) throw new IOException("MIDI 运行状态错误"); }
                else if(status<240) running=status;
                if(status==255) {
                    int type=data.readUnsignedByte(), length=vlq(data);
                    if(length>data.available()) throw new IOException("MIDI 元事件截断");
                    if(type==81 && length==3) {
                        int tempo=(data.readUnsignedByte()<<16)|(data.readUnsignedByte()<<8)|data.readUnsignedByte();
                        if(tempo<=0) throw new IOException("MIDI 速度错误"); file.tempos.put(tick,tempo);
                    } else data.skipBytes(length);
                    if(type==47) break;
                } else if(status==240 || status==247) {
                    int length=vlq(data); if(length>data.available()) throw new IOException("MIDI 系统事件截断");
                    data.skipBytes(length); running=-1;
                } else if(status>=128 && status<240) {
                    int kind=status&240, channel=(status&15)+1;
                    int a=first>=0?first:data.readUnsignedByte();
                    int b=(kind==192 || kind==208)?0:data.readUnsignedByte();
                    if(a>127 || b>127) throw new IOException("MIDI 音符数据错误");
                    int key=(channel<<8)|a;
                    if(kind==144 && b>0) active.computeIfAbsent(key,k->new ArrayDeque<>()).add(new long[]{tick,b});
                    else if(kind==128 || (kind==144 && b==0)) {
                        ArrayDeque<long[]> queue=active.get(key);
                        if(queue!=null && !queue.isEmpty()) {
                            long[] start=queue.remove();
                            if(tick>start[0]) file.notes.add(new Note(start[0],tick,a,(int)start[1],channel));
                            if(file.notes.size()>100000) throw new IOException("音符数量超过上限");
                        }
                    }
                } else throw new IOException("不支持的 MIDI 事件");
            }
        }
        file.notes.sort(Comparator.comparingLong((Note n)->n.startTick).thenComparingInt(n->n.pitch));
        for(Note n:file.notes) { n.start=file.seconds(n.startTick); n.duration=file.seconds(n.endTick)-n.start; }
        if(file.notes.isEmpty()) throw new IOException("没有可演奏的音符");
        return file;
    }
    double seconds(long tick) {
        long previous=0; int tempo=500000; double sec=0;
        for(Map.Entry<Long,Integer> e:tempos.entrySet()) {
            if(e.getKey()>tick) break;
            sec+=(e.getKey()-previous)*tempo/(double)division/1e6;
            previous=e.getKey(); tempo=e.getValue();
        }
        return sec+(tick-previous)*tempo/(double)division/1e6;
    }
    /** For same-onset chords use the highest note; do not mix percussion into the melody. */
    public List<Note> melody(int channel) {
        TreeMap<Long,Note> chosen=new TreeMap<>();
        for(Note n:notes) if(n.channel==channel && channel!=10) {
            Note old=chosen.get(n.startTick); if(old==null || old.pitch<n.pitch) chosen.put(n.startTick,n);
        }
        return new ArrayList<>(chosen.values());
    }
    public static String name(int p) { return new String[]{"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"}[Math.floorMod(p,12)]+(p/12-1); }
}
