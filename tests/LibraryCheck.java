package cn.luxen.harmonica;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class LibraryCheck {
    private static void require(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("harmonica-library-check-").toFile();
        SongLibrary library=new SongLibrary(new File(root,"library"));
        File first=new File(args[0]),second=new File(args[1]);
        String originalHash=SongLibrary.contentId(first);
        SongLibrary.Song a=library.add(first,"中文曲目.mid",8,"0.75","-2");
        SongLibrary.Song b=library.add(second,"中文曲目.mid",1,"1","0");
        require(!a.id.equals(b.id) && library.list().size()==2,"same-name different files must both remain");
        require(SongLibrary.contentId(a.file).equals(originalHash),"imported copy must preserve exact bytes");
        library.save(a,3,"0.5","-5");
        SongLibrary.Song again=library.add(first,"改名后再导入.mid",1,"1","0");
        require(again.id.equals(a.id) && library.list().size()==2,"renamed duplicate must not overwrite or duplicate");
        require(again.channel==3 && again.speed.equals("0.5") && again.transpose.equals("-5"),"duplicate must preserve song settings");
        SongLibrary reopened=new SongLibrary(new File(root,"library"));
        SongLibrary.Song restored=null;
        for(SongLibrary.Song song:reopened.list()) if(song.id.equals(a.id)) restored=song;
        require(restored!=null && restored.name.equals("中文曲目.mid") && restored.channel==3,"name and settings must survive reopening");
        reopened.delete(restored);
        require(!restored.file.exists() && reopened.list().size()==1,"delete only the selected local copy");
        require(first.isFile() && originalHash.equals(SongLibrary.contentId(first)),"deletion must not affect original imported file");
        require(reopened.list().get(0).id.equals(b.id),"other songs must remain");
        System.out.println("PASS exact copies, Unicode names, same-name songs, duplicate import, persistent settings and scoped deletion");
    }
}
