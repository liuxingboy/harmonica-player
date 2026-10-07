package cn.luxen.harmonica;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private TextView status,summary;
    private Spinner channels,songs;
    private Button deleteSong;
    private SongLibrary library;
    private SongLibrary.Song selectedSong;
    private final List<SongLibrary.Song> savedSongs=new ArrayList<>();
    private boolean loadingSong;
    private static final String BUILTIN="builtin";
    private EditText speed,transpose;
    private CheckBox confirm;
    private MidiFile midi;
    private final List<Integer> channelIds=new ArrayList<>();
    private String title="极乐净土·主旋律（43秒）";
    private SharedPreferences prefs;
    private LinearLayout body;
    private int dp(int x) { return Math.round(x*getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs=getSharedPreferences("player",MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(1);
        body.setPadding(dp(18),dp(18),dp(18),dp(28)); scroll.addView(body); setContentView(scroll);
        text("口风琴播放器",25);
        text("导入 MIDI 后会保存在本地曲库，可选择演奏或删除。按单旋律处理，同一时刻的和弦保留最高音。",15);
        status=text("",15);
        text("本地曲库 · 选择要演奏的曲目",16);
        songs=new Spinner(this);body.addView(songs);
        songs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                String wanted=position==0?BUILTIN:savedSongs.get(position-1).id;
                if(wanted.equals(currentSongId())) return;
                try { rememberCurrent();selectSong(wanted); }
                catch(Exception e) { message(e.getMessage());songs.setSelection(currentSongPosition()); }
            }
        });
        deleteSong=button("删除所选曲目",()->confirmDelete());
        button("1 · 开启触摸控制权限",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("使用极乐净土 · 43秒主旋律",()->{try{rememberCurrent();selectSong(BUILTIN);songs.setSelection(0);}catch(Exception e){message(e.getMessage());}});
        button("导入 MIDI 文件",()->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
            startActivityForResult(intent,42);
        });
        text("旋律通道（不使用打击乐通道10）",16);
        channels=new Spinner(this); body.addView(channels);
        channels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { updateSummary(); }
        });
        summary=text("",16);
        text("速度：1 为原速，0.75 为慢速",15); speed=new EditText(this);
        speed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        speed.setText(prefs.getString("speed","1")); body.addView(speed);
        text("整体移调（半音）：本曲推荐 -2",15); transpose=new EditText(this);
        transpose.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        transpose.setText(prefs.getString("transpose","-2")); body.addView(transpose);
        confirm=new CheckBox(this); confirm.setText("我会先打开游戏口风琴琴键页面，再点击浮窗播放"); body.addView(confirm);
        button("2 · 保存配置并打开游戏浮窗",()->openPlayer());
        button("停止并关闭浮窗",()->{ if(PlayerService.instance!=null) PlayerService.instance.hidePanel(); });
        text("初始键位已按真我 GT 横屏页面校准。换设备、改分辨率或移动游戏按钮后，点击浮窗“校准”拖动标记。",14);
        text("只有游戏前台且横屏时才会演奏。不要在演奏中手动改半音或八度。普通触摸自动化仍可能违反游戏规则，无法保证免封号。",14);
        try {
            library=new SongLibrary(new File(getFilesDir(),"midi-library"));
            migratePreviousSong();refreshSongs(prefs.getString("selected_song",BUILTIN));
        } catch(Exception e) { message(e.getMessage());loadBuiltin(false); }
    }
    @Override protected void onPause() {
        super.onPause();try { rememberCurrent(); } catch(Exception e) { Toast.makeText(this,"曲目配置未保存："+e.getMessage(),Toast.LENGTH_LONG).show(); }
    }
    @Override public void onResume() {
        super.onResume(); refreshStatus();
        if(status!=null){status.postDelayed(()->refreshStatus(),1000);status.postDelayed(()->refreshStatus(),2500);}
    }
    private void refreshStatus(){if(status!=null && !isFinishing())status.setText(PlayerService.instance==null?"触摸控制：未开启，请先点击第1步":"触摸控制：已开启，无需 root 或悬浮窗权限");}
    private TextView text(String value,int size) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setPadding(0,dp(6),0,dp(6)); body.addView(v); return v;
    }
    private Button button(String label,Runnable action) {
        Button b=new Button(this); b.setText(label); body.addView(b); b.setOnClickListener(v->{try{action.run();}catch(Exception e){message(e.getMessage());}});
        return b;
    }
    private void message(String value) { new AlertDialog.Builder(this).setMessage(value==null?"操作失败":value).setPositiveButton("知道了",null).show(); }
    private void loadBuiltin(boolean replace) {
        try(InputStream in=getAssets().open("gokuraku.mid")) {
            midi=MidiFile.read(in);title="极乐净土·主旋律（43秒）";
            setMidi(midi,prefs.getInt("builtin_channel",1));
            speed.setText(prefs.getString("builtin_speed","1"));transpose.setText(prefs.getString("builtin_transpose","-2"));
        } catch(Exception e) { message(e.getMessage()); }
    }
    private void setMidi(MidiFile file,int selectedChannel) {
        midi=file; channelIds.clear(); List<String> labels=new ArrayList<>();
        for(int channel=1;channel<=16;channel++) {
            List<MidiFile.Note> notes=file.melody(channel); if(notes.isEmpty()) continue;
            int min=127,max=0; for(MidiFile.Note n:notes){min=Math.min(min,n.pitch);max=Math.max(max,n.pitch);}
            channelIds.add(channel); labels.add("通道 "+channel+" · "+notes.size()+" 音符 · "+MidiFile.name(min)+"～"+MidiFile.name(max));
        }
        channels.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        int index=channelIds.indexOf(selectedChannel);channels.setSelection(Math.max(0,index));
        updateSummary();
    }
    private String currentSongId() { return selectedSong==null?BUILTIN:selectedSong.id; }
    private int currentSongPosition() {
        for(int i=0;i<savedSongs.size();i++) if(savedSongs.get(i).id.equals(currentSongId())) return i+1;
        return 0;
    }
    private void rememberCurrent() throws IOException {
        if(loadingSong || midi==null || library==null || channelIds.isEmpty()) return;
        int channel=channelIds.get(Math.max(0,channels.getSelectedItemPosition()));
        String rate=speed.getText().toString(),shift=transpose.getText().toString();
        if(selectedSong!=null) library.save(selectedSong,channel,rate,shift);
        else prefs.edit().putInt("builtin_channel",channel).putString("builtin_speed",rate).putString("builtin_transpose",shift).apply();
    }
    private void migratePreviousSong() throws Exception {
        if(prefs.getBoolean("library_ready",false)) return;
        File previous=new File(getFilesDir(),"selected.mid");String id=BUILTIN;
        if(previous.isFile()) {
            MidiFile parsed;try(InputStream in=new FileInputStream(previous)){parsed=MidiFile.read(in);}
            File builtin=new File(getCacheDir(),"builtin-migration.mid");
            try {
                try(InputStream in=getAssets().open("gokuraku.mid");OutputStream out=new FileOutputStream(builtin)){copy(in,out);}
                if(!SongLibrary.contentId(previous).equals(SongLibrary.contentId(builtin))) {
                    SongLibrary.Song song=library.add(previous,prefs.getString("title","之前导入的 MIDI"),prefs.getInt("channel",1),prefs.getString("speed","1"),prefs.getString("transpose","-2"));id=song.id;
                } else prefs.edit().putInt("builtin_channel",prefs.getInt("channel",1)).putString("builtin_speed",prefs.getString("speed","1")).putString("builtin_transpose",prefs.getString("transpose","-2")).apply();
            } finally { builtin.delete(); }
        }
        if(!prefs.edit().putBoolean("library_ready",true).putString("selected_song",id).commit()) throw new IOException("无法保存曲库配置");
        // Migration is committed only after the copied MIDI and its metadata are durable.
        if(previous.isFile() && !previous.delete()) throw new IOException("曲目已保存，但旧缓存清理失败");
    }
    private void refreshSongs(String wanted) throws Exception {
        savedSongs.clear();savedSongs.addAll(library.list());List<String> names=new ArrayList<>();
        names.add("内置 · 极乐净土（43秒）");for(SongLibrary.Song song:savedSongs) names.add(song.name);
        int position=0;for(int i=0;i<savedSongs.size();i++) if(savedSongs.get(i).id.equals(wanted)) position=i+1;
        selectSong(position==0?BUILTIN:savedSongs.get(position-1).id);
        songs.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));songs.setSelection(position);
    }
    private void selectSong(String id) throws Exception {
        SongLibrary.Song next=null;
        if(!BUILTIN.equals(id)) {
            for(SongLibrary.Song song:savedSongs) if(song.id.equals(id)) next=song;
            if(next==null) throw new IOException("曲目已不存在，请重新导入");
        }
        MidiFile parsed=null;if(next!=null) try(InputStream in=new FileInputStream(next.file)){parsed=MidiFile.read(in);}
        if(PlayerService.instance!=null) PlayerService.instance.hidePanel();
        loadingSong=true;
        try {
            selectedSong=next;
            if(next==null) loadBuiltin(false);
            else { title=next.name;setMidi(parsed,next.channel);speed.setText(next.speed);transpose.setText(next.transpose); }
            deleteSong.setEnabled(next!=null);
            prefs.edit().putString("selected_song",id).putString("title",title).remove("song").apply();
        } finally { loadingSong=false; }
    }
    private void confirmDelete() {
        final SongLibrary.Song deleting=selectedSong;if(deleting==null) return;
        new AlertDialog.Builder(this).setTitle("删除曲目？").setMessage("删除本地曲库中的“"+deleting.name+"”？\n不会删除你导入时选择的原文件。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{
                try {
                    if(PlayerService.instance!=null) PlayerService.instance.hidePanel();
                    library.delete(deleting);prefs.edit().remove("song").apply();
                    refreshSongs(BUILTIN);Toast.makeText(this,"已删除曲目",Toast.LENGTH_SHORT).show();
                } catch(Exception e) { message("删除失败："+e.getMessage()); }
            }).show();
    }
    private List<MidiFile.Note> selectedNotes() {
        if(midi==null || channelIds.isEmpty()) throw new IllegalArgumentException("请先选择旋律 MIDI");
        return midi.melody(channelIds.get(Math.max(0,channels.getSelectedItemPosition())));
    }
    private void updateSummary() {
        if(summary==null || midi==null || channelIds.isEmpty()) return;
        List<MidiFile.Note> notes=selectedNotes(); MidiFile.Note last=notes.get(notes.size()-1);
        summary.setText(title+"\n"+String.format(Locale.CHINA,"旋律 %.1f 秒 · %d 个音符\n已去掉开头空白；可选通道共有 %d 个",last.start+last.duration-notes.get(0).start,notes.size(),channelIds.size()));
    }
    private void openPlayer() {
        try {
            if(!confirm.isChecked()) throw new IllegalArgumentException("请确认将在口风琴琴键页面演奏");
            if(PlayerService.instance==null) throw new IllegalArgumentException("请先开启“口风琴触摸控制”无障碍服务");
            double rate=Double.parseDouble(speed.getText().toString()); int shift=Integer.parseInt(transpose.getText().toString());
            List<MidiFile.Note> notes=selectedNotes(); Planner.build(notes,rate,shift);
            rememberCurrent();
            JSONArray data=new JSONArray();
            for(MidiFile.Note n:notes) data.put(new JSONObject().put("start",n.start).put("duration",n.duration).put("pitch",n.pitch));
            prefs.edit().putString("song",data.toString()).putString("title",title).putString("speed",speed.getText().toString())
                .putString("transpose",transpose.getText().toString()).putInt("channel",channelIds.get(channels.getSelectedItemPosition())).apply();
            PlayerService.instance.showPanel();
            Intent game=getPackageManager().getLaunchIntentForPackage(PlayerService.GAME);
            if(game!=null) startActivity(game); else throw new IllegalArgumentException("手机上未找到三角洲行动，请手动打开游戏");
        } catch(Exception e) { message(e.getMessage()); }
    }
    private static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buf=new byte[8192]; int total=0,n;
        while((n=in.read(buf))!=-1){ total+=n; if(total>5_000_000)throw new IOException("MIDI 最大支持 5 MB"); out.write(buf,0,n); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=42 || result!=RESULT_OK || data==null || data.getData()==null) return;
        File temporary=new File(getFilesDir(),"importing.mid");
        try {
            Uri uri=data.getData();
            try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(temporary)) {
                if(in==null) throw new IOException("无法读取选中的文件"); copy(in,out);
            }
            MidiFile parsed;
            try(InputStream in=new FileInputStream(temporary)) { parsed=MidiFile.read(in); }
            String name="导入的 MIDI";
            try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if(cursor!=null && cursor.moveToFirst()) name=cursor.getString(0);
            }
            boolean melodic=false;for(int c=1;c<=16;c++) if(!parsed.melody(c).isEmpty()) melodic=true;
            if(!melodic) throw new IOException("MIDI 只有打击乐，没有可演奏的旋律通道");
            rememberCurrent();SongLibrary.Song song=library.add(temporary,name,1,speed.getText().toString(),transpose.getText().toString());
            refreshSongs(song.id);Toast.makeText(this,"已保存到本地曲库："+song.name,Toast.LENGTH_LONG).show();
        } catch(Exception e) { message("导入失败："+e.getMessage()); }
        finally { temporary.delete(); }
    }
}
