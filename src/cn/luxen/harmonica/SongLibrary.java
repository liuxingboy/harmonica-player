package cn.luxen.harmonica;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** App-private MIDI copies. Content IDs deduplicate imports independently of file names. */
public final class SongLibrary {
    public static final class Song {
        public final String id,name;
        public final File file;
        public final long added;
        public int channel;
        public String speed,transpose;
        Song(String id,String name,File file,long added,int channel,String speed,String transpose) {
            this.id=id;this.name=name;this.file=file;this.added=added;
            this.channel=channel;this.speed=speed;this.transpose=transpose;
        }
    }
    private final File directory;
    public SongLibrary(File directory) throws IOException {
        this.directory=directory;
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本地曲库");
    }
    public static String contentId(File file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(file)) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) digest.update(buffer,0,n);
            }
            StringBuilder id=new StringBuilder();
            for(byte b:digest.digest()) id.append(String.format(Locale.ROOT,"%02x",b&255));
            return id.toString();
        } catch(NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private File metadata(String id) { return new File(directory,id+".properties"); }
    private Song read(String id) throws IOException {
        if(!id.matches("[0-9a-f]{64}")) throw new IOException("曲目编号错误");
        Properties p=new Properties();
        try(InputStream in=new FileInputStream(metadata(id))) { p.load(in); }
        File file=new File(directory,id+".mid");
        if(!file.isFile()) throw new FileNotFoundException("本地 MIDI 已不存在");
        try {
            return new Song(id,p.getProperty("name","未命名 MIDI"),file,
                Long.parseLong(p.getProperty("added","0")),Integer.parseInt(p.getProperty("channel","1")),
                p.getProperty("speed","1"),p.getProperty("transpose","-2"));
        } catch(NumberFormatException e) { throw new IOException("曲目配置损坏",e); }
    }
    public List<Song> list() throws IOException {
        File[] files=directory.listFiles((dir,name)->name.matches("[0-9a-f]{64}\\.properties"));
        if(files==null) throw new IOException("无法读取本地曲库");
        List<Song> songs=new ArrayList<>();
        for(File file:files) {
            try { songs.add(read(file.getName().substring(0,64))); }
            catch(FileNotFoundException ignored) { /* An interrupted deletion has no selectable MIDI. */ }
        }
        songs.sort(Comparator.comparingLong((Song s)->s.added).reversed().thenComparing(s->s.id));
        return songs;
    }
    private static void replace(File from,File to) throws IOException {
        try { Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING); }
    }
    public Song add(File source,String name,int channel,String speed,String transpose) throws IOException {
        String id=contentId(source);
        if(metadata(id).isFile() && new File(directory,id+".mid").isFile()) return read(id);
        File target=new File(directory,id+".mid");
        File temporary=File.createTempFile("import-",".tmp",directory);
        try {
            try(InputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temporary)) {
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                out.getFD().sync();
            }
            replace(temporary,target);
            Song song=new Song(id,name==null||name.trim().isEmpty()?"导入的 MIDI":name,target,System.currentTimeMillis(),channel,speed,transpose);
            save(song,channel,speed,transpose);return song;
        } finally { temporary.delete(); }
    }
    public void save(Song song,int channel,String speed,String transpose) throws IOException {
        Properties p=new Properties();p.setProperty("name",song.name);p.setProperty("added",Long.toString(song.added));
        p.setProperty("channel",Integer.toString(channel));p.setProperty("speed",speed);p.setProperty("transpose",transpose);
        File temporary=File.createTempFile("settings-",".tmp",directory);
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)) { p.store(out,"MIDI song");out.getFD().sync(); }
            replace(temporary,metadata(song.id));song.channel=channel;song.speed=speed;song.transpose=transpose;
        } finally { temporary.delete(); }
    }
    public void delete(Song song) throws IOException {
        Files.deleteIfExists(song.file.toPath());Files.deleteIfExists(metadata(song.id).toPath());
    }
}
