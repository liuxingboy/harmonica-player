package cn.luxen.harmonica;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import org.json.*;
import java.util.*;

public class PlayerService extends AccessibilityService {
    public static final String GAME="com.tencent.tmgp.dfm";
    public static PlayerService instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private SharedPreferences prefs;
    private View panel,calibration;
    private TextView label;
    private WindowManager.LayoutParams panelParams;
    private float[][] points;
    private List<Planner.Step> steps;
    private int index,token,octave;
    private long base;
    private boolean running,paused,sharp,busy;
    private Runnable scheduled;
    private java.io.PrintWriter trace;
    private static final float[][] DEFAULT={{.15486f,.60764f},{.25382f,.60764f},{.35174f,.60764f},{.45069f,.60764f},
        {.54931f,.60764f},{.64757f,.60764f},{.74618f,.60764f},{.84583f,.60764f},
        {.34722f,.42708f},{.46875f,.42708f},{.56076f,.42708f},{.65278f,.42708f}};
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private Point screen(){Point p=new Point(); windows.getDefaultDisplay().getRealSize(p); return p;}
    @Override protected void onServiceConnected(){super.onServiceConnected();instance=this;windows=(WindowManager)getSystemService(WINDOW_SERVICE);prefs=getSharedPreferences("player",MODE_PRIVATE);loadPoints();
        try{trace=new java.io.PrintWriter(new java.io.FileOutputStream(new java.io.File(getFilesDir(),"playback.log"),true));}catch(java.io.IOException ignored){}
        log("service connected");}
    @Override public void onInterrupt(){halt("触摸控制中断",true);}
    @Override public void onDestroy(){log("service destroyed");hidePanel();if(trace!=null)trace.close();instance=null;super.onDestroy();}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(running && !isGame()) halt("已离开游戏，演奏停止",false);
    }
    private void loadPoints(){points=new float[12][2];for(int i=0;i<12;i++){points[i][0]=prefs.getFloat("x"+i,DEFAULT[i][0]);points[i][1]=prefs.getFloat("y"+i,DEFAULT[i][1]);}}
    private boolean isGame(){
        for(AccessibilityWindowInfo w:getWindows()) if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && w.isFocused()){
            AccessibilityNodeInfo root=w.getRoot();if(root!=null){CharSequence p=root.getPackageName(); boolean ok=GAME.contentEquals(p==null?"":p);root.recycle();return ok;}
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;
        CharSequence p=root.getPackageName();boolean ok=GAME.contentEquals(p==null?"":p);root.recycle();return ok;
    }
    private boolean ready(){
        Point s=screen();float expected=prefs.getFloat("aspect",2f);
        if(s.x<=s.y || Math.abs(s.x/(float)s.y-expected)>.04f){status("请横屏并校准当前分辨率");return false;}
        if(!isGame()){status("请先打开游戏口风琴页面");return false;}return true;
    }
    private void log(String text){android.util.Log.i("HarmonicaPlayer",text);if(trace!=null){trace.println(System.currentTimeMillis()+" "+text);trace.flush();}}
    private void status(String text){if(label!=null)label.setText(text);log(text);}
    private WindowManager.LayoutParams params(int width,int height){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        p.gravity=Gravity.TOP|Gravity.LEFT;return p;
    }
    public void showPanel(){
        if(panel!=null)return;
        log("show panel");
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(5),dp(3),dp(5),dp(3));box.setBackgroundColor(0xe6222935);
        label=new TextView(this);label.setTextColor(Color.WHITE);label.setTextSize(13);label.setSingleLine(true);label.setMaxWidth(dp(300));label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setText("口风琴 · 拖动标题可移动");box.addView(label);
        LinearLayout row=new LinearLayout(this);box.addView(row);
        addButton(row,"播放",()->startOrResume());addButton(row,"暂停",()->pause());addButton(row,"停止",()->halt("已停止",true));
        addButton(row,"校准",()->calibrate());addButton(row,"关闭",()->hidePanel());
        panel=box;panelParams=params(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT);
        panelParams.x=dp(12);panelParams.y=dp(40);windows.addView(panel,panelParams);
        label.setOnTouchListener(new View.OnTouchListener(){float x,y;int px,py;
            public boolean onTouch(View v,android.view.MotionEvent e){
                if(e.getAction()==0){x=e.getRawX();y=e.getRawY();px=panelParams.x;py=panelParams.y;return true;}
                if(e.getAction()==2){Point s=screen();panelParams.x=Math.max(0,Math.min(s.x-panel.getWidth(),px+(int)(e.getRawX()-x)));
                    panelParams.y=Math.max(0,Math.min(s.y-panel.getHeight(),py+(int)(e.getRawY()-y)));windows.updateViewLayout(panel,panelParams);return true;}return true;
            }});
    }
    private void addButton(LinearLayout row,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(7),0,dp(7),0);row.addView(b);b.setOnClickListener(v->action.run());}
    public void hidePanel(){halt("已关闭",true);if(calibration!=null){windows.removeView(calibration);calibration=null;}if(panel!=null){windows.removeView(panel);panel=null;label=null;}}
    private void clearScheduled(){if(scheduled!=null)handler.removeCallbacks(scheduled);scheduled=null;}
    private void halt(String reason,boolean cancel){
        token++;running=false;paused=false;clearScheduled();
        // A mode-control touch cancels any pending note gesture and releases its pointer.
        if(cancel && busy && isGame()) tap(10,()->{});
        busy=false;status(reason);
    }
    private void pause(){
        if(!running)return;paused=true;running=false;clearScheduled();status("已暂停；当前音符松开后可继续");
    }
    private List<MidiFile.Note> readSong()throws Exception{
        JSONArray array=new JSONArray(prefs.getString("song","[]"));List<MidiFile.Note> notes=new ArrayList<>();
        for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);MidiFile.Note n=new MidiFile.Note(0,0,o.getInt("pitch"),100,1);n.start=o.getDouble("start");n.duration=o.getDouble("duration");notes.add(n);}return notes;
    }
    private void startOrResume(){
        if(running || busy){status("正在演奏或等待当前触摸完成");return;}if(!ready())return;
        if(paused && steps!=null && index<steps.size()){
            running=true;paused=false;base=SystemClock.uptimeMillis()+350-steps.get(index).start;schedule();return;
        }
        try{
            steps=Planner.build(readSong(),Double.parseDouble(prefs.getString("speed","1")),Integer.parseInt(prefs.getString("transpose","-2")));
            index=0;token++;status("检查琴键模式…");final int run=token;
            inspectModes(new ModeCallback(){public void result(int detectedOctave,boolean detectedSharp){
                if(run!=token)return;octave=detectedOctave;sharp=detectedSharp;
                Runnable begin=()->{if(run!=token)return;octave=0;sharp=false;running=true;paused=false;base=SystemClock.uptimeMillis();schedule();};
                if(sharp)tap(8,()->tap(10,begin));else tap(10,begin);
            }});
        }catch(Exception e){status(e.getMessage());}
    }
    private interface ModeCallback{void result(int octave,boolean sharp);}
    private void inspectModes(ModeCallback callback){
        final int requestToken=token;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            public void onSuccess(ScreenshotResult result){
                if(requestToken!=token){result.getHardwareBuffer().close();return;}
                Bitmap hardware=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                Bitmap bitmap=hardware==null?null:hardware.copy(Bitmap.Config.ARGB_8888,false);
                if(hardware!=null)hardware.recycle();result.getHardwareBuffer().close();
                if(bitmap==null){status("无法检查模式，请重新打开口琴页面");return;}
                try{
                    if(!looksLikeKeyboard(bitmap)){status("未检测到八个琴键，请先打开口风琴弹奏页面");return;}
                    double up=brightness(bitmap,9),normal=brightness(bitmap,10),down=brightness(bitmap,11),half=brightness(bitmap,8);
                    double[] values={down,normal,up};int mode=0;for(int i=1;i<3;i++)if(values[i]>values[mode])mode=i;
                    double second=-1;for(int i=0;i<3;i++)if(i!=mode)second=Math.max(second,values[i]);
                    log("mode luminance up="+up+" normal="+normal+" down="+down+" half="+half);
                    if(values[mode]-second<22){status("未识别到琴键页面，请打开口琴或重新校准");return;}
                    double unselected=0;for(int i=0;i<3;i++)if(i!=mode)unselected+=values[i]/2;
                    callback.result(mode-1,half>unselected+28);
                }finally{bitmap.recycle();}
            }
            public void onFailure(int error){if(requestToken==token)status("截图检查失败（"+error+"），请重试或打开投屏");}
        });
    }
    private double brightness(Bitmap bitmap,int key){
        int x=Math.round(points[key][0]*bitmap.getWidth()),y=Math.round((points[key][1]-.031f)*bitmap.getHeight());
        double sum=0;int count=0;
        for(int dx=-6;dx<=6;dx+=3)for(int dy=-6;dy<=6;dy+=3){int px=Math.max(0,Math.min(bitmap.getWidth()-1,x+dx)),py=Math.max(0,Math.min(bitmap.getHeight()-1,y+dy));int c=bitmap.getPixel(px,py);sum+=(Color.red(c)+Color.green(c)+Color.blue(c))/3.;count++;}return sum/count;
    }
    private boolean looksLikeKeyboard(Bitmap bitmap){
        int found=0,radius=Math.max(8,Math.round(bitmap.getHeight()*.022f));
        for(int key=0;key<8;key++){
            int x=Math.round(points[key][0]*bitmap.getWidth()),y=Math.round(points[key][1]*bitmap.getHeight());
            int white=0,total=0;
            for(int dx=-radius;dx<=radius;dx+=5)for(int dy=-radius;dy<=radius;dy+=5){
                int px=Math.max(0,Math.min(bitmap.getWidth()-1,x+dx)),py=Math.max(0,Math.min(bitmap.getHeight()-1,y+dy));
                int c=bitmap.getPixel(px,py);if((Color.red(c)+Color.green(c)+Color.blue(c))/3>180)white++;total++;
            }
            float ratio=white/(float)total;if(ratio>.08f && ratio<.55f)found++;
        }
        log("keyboard digit regions="+found+"/8");return found>=7;
    }
    private void tap(int key,Runnable done){
        if(!ready())return;Point s=screen();Path p=new Path();p.moveTo(points[key][0]*s.x,points[key][1]*s.y);
        final int requestToken=token;busy=true;
        boolean accepted=dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,30)).build(),new GestureResultCallback(){
            public void onCompleted(GestureDescription g){busy=false;if(requestToken==token)handler.postDelayed(done,35);}
            public void onCancelled(GestureDescription g){busy=false;if(requestToken==token)halt("触摸被中断，请重试",false);}
        },handler);
        if(!accepted){busy=false;halt("系统未接受触摸，请检查无障碍权限",false);}
    }
    private void schedule(){
        if(!running)return;
        if(index>=steps.size()){
            running=false;status("演奏完成 · "+steps.size()+" 个音符");
            Runnable restore=()->tap(10,()->{octave=0;status("演奏完成，已恢复自然音");});
            if(sharp)tap(8,()->{sharp=false;restore.run();});else restore.run();return;
        }
        final int run=token;scheduled=()->{if(run==token && running)perform();};handler.postAtTime(scheduled,base+steps.get(index).start);
    }
    private void perform(){
        if(!ready()){halt("游戏或屏幕状态变化，已停止",false);return;}
        Planner.Step step=steps.get(index);long late=SystemClock.uptimeMillis()-(base+step.start);
        if(late>90){halt("触摸调度落后，请降低播放速度",false);return;}
        GestureDescription.Builder gesture=new GestureDescription.Builder();Point size=screen();
        long at=0;for(int control:step.controls){Path p=new Path();p.moveTo(points[control][0]*size.x,points[control][1]*size.y);gesture.addStroke(new GestureDescription.StrokeDescription(p,at,Planner.CONTROL_MS));at+=Planner.CONTROL_MS+Planner.CONTROL_GAP_MS;}
        Path p=new Path();p.moveTo(points[step.key.index][0]*size.x,points[step.key.index][1]*size.y);gesture.addStroke(new GestureDescription.StrokeDescription(p,at,step.hold));
        final int run=token;busy=true;status("弹奏 "+(index+1)+"/"+steps.size()+" · "+MidiFile.name(step.pitch));
        log("note="+index+" pitch="+step.pitch+" late_ms="+late+" hold_ms="+step.hold);
        boolean accepted=dispatchGesture(gesture.build(),new GestureResultCallback(){
            public void onCompleted(GestureDescription g){busy=false;if(run!=token)return;octave=step.key.octave;sharp=step.key.sharp;index++;if(running)schedule();}
            public void onCancelled(GestureDescription g){busy=false;if(run==token)halt("触摸被取消，已停止；请重试",false);}
        },handler);
        if(!accepted){busy=false;halt("触摸发送失败，已停止",false);}
    }
    private void calibrate(){
        halt("校准键位",true);if(!ready())return;
        if(panel!=null){windows.removeView(panel);panel=null;label=null;}
        calibration=new CalibrationView();windows.addView(calibration,params(-1,-1));
    }
    private class CalibrationView extends View{
        Paint paint=new Paint(3);int dragging=-1;
        String[] names={"1","2","3","4","5","6","7","高1","半音","升调","自然","降调"};
        CalibrationView(){super(PlayerService.this);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);paint.setColor(0xb5222935);canvas.drawRect(0,0,getWidth(),dp(64),paint);
            paint.setColor(Color.WHITE);paint.setTextSize(dp(14));canvas.drawText("拖动圆点到对应按钮中心 · 点右上角保存",dp(15),dp(30),paint);
            paint.setColor(0xff66eecc);canvas.drawText("保存",getWidth()-dp(64),dp(30),paint);
            for(int i=0;i<12;i++){float x=points[i][0]*getWidth(),y=points[i][1]*getHeight();paint.setColor(0xb522aabb);canvas.drawCircle(x,y,dp(17),paint);paint.setColor(Color.WHITE);paint.setTextSize(dp(13));canvas.drawText(names[i],x-dp(10),y+dp(5),paint);}
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(e.getAction()==0){
                if(e.getY()<dp(64) && e.getX()>getWidth()-dp(90)){
                    SharedPreferences.Editor edit=prefs.edit().putFloat("aspect",getWidth()/(float)getHeight());
                    for(int i=0;i<12;i++)edit.putFloat("x"+i,points[i][0]).putFloat("y"+i,points[i][1]);edit.apply();
                    windows.removeView(calibration);calibration=null;showPanel();status("键位已保存");return true;
                }
                float distance=dp(45);for(int i=0;i<12;i++){float dx=e.getX()-points[i][0]*getWidth(),dy=e.getY()-points[i][1]*getHeight();float d=(float)Math.hypot(dx,dy);if(d<distance){distance=d;dragging=i;}}
            }else if(e.getAction()==2 && dragging>=0){points[dragging][0]=Math.max(.01f,Math.min(.99f,e.getX()/getWidth()));points[dragging][1]=Math.max(.08f,Math.min(.98f,e.getY()/getHeight()));invalidate();}
            else if(e.getAction()==1 || e.getAction()==3)dragging=-1;
            return true;
        }
    }
}
