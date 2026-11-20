package org.aliveclean;

import android.app.KeyguardManager;
import android.content.*;
import android.os.Bundle;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public final class CleanWallpaper extends WallpaperService {
    @Override public Engine onCreateEngine(){return new SceneEngine();}
    final class SceneEngine extends Engine implements SceneChannel.Listener {
        RenderLoop renderer;
        boolean ambient;
        final SceneState scenesState=new SceneState();
        boolean regionObserverRegistered,receivedLayout;
        boolean wallpaperVisible,renderVisible;
        int lastDisplayState=android.view.Display.STATE_UNKNOWN;
        android.hardware.display.DisplayManager displayManager;
        final android.hardware.display.DisplayManager.DisplayListener displays=new android.hardware.display.DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){refreshVisibility();}
            public void onDisplayRemoved(int id){refreshVisibility();}
            public void onDisplayChanged(int id){if(id==engineDisplayId())refreshVisibility();}
        };
        final android.database.ContentObserver area=new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())){
            @Override public void onChange(boolean selfChange){readOfficialRegion();}
        };
        /**
         * Platform screen events drive the scene machine directly in the wallpaper
         * process. This does NOT depend on LSPosed: three diagnostic dumps showed
         * the module is injected into system_server but never into SystemUI on
         * this NyaZygisk/KernelSU build. Without this, no one ever sends mode 0
         * (AOD) and the lockscreen/desktop only work because KeyguardManager
         * fallback produces modes 1/2.
         */
        final BroadcastReceiver state=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            String action=intent.getAction();
            if(Intent.ACTION_CONFIGURATION_CHANGED.equals(action))readWallpaperDim();
            if(!isPreview()){
                long now=android.os.SystemClock.uptimeMillis();
                int target=-1,eventPhase=0;
                if(Intent.ACTION_SCREEN_OFF.equals(action)||Intent.ACTION_DREAMING_STARTED.equals(action)){target=0;eventPhase=1;}
                else if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_DREAMING_STOPPED.equals(action)){target=1;eventPhase=2;}
                else if(Intent.ACTION_USER_PRESENT.equals(action)){target=2;eventPhase=3;}
                if(target>=0){
                    // Unconditional: Diagnostics.TRACE=false in production,
                    // so gating this behind it made every diagnostic dump blind.
                    android.util.Log.i("AliveClean","Screen event="+action+" -> mode "+target+" phase="+eventPhase);
                    scene(target,true,now,eventPhase,0,Float.NaN,Float.NaN);
                }
            }
            update();refreshVisibility();
        }};
        final BroadcastReceiver scenes=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
            if(isPreview())return;
            if(AospSceneLink.ACTION_LAYOUT.equals(intent.getAction())){
                if(intent.getIntExtra("version",0)!=1)return;
                AodRegion region=AodRegion.create(intent.getIntExtra("display",-1),intent.getIntExtra("width",0),intent.getIntExtra("height",0),intent.getIntExtra("left",0),intent.getIntExtra("top",0),intent.getIntExtra("right",0),intent.getIntExtra("bottom",0));
                region(region);
                return;
            }
            if(!AospSceneLink.ACTION.equals(intent.getAction()))return;
            int next=intent.getIntExtra("mode",-1);
            if(next>=0&&next<=2)scene(next,intent.getBooleanExtra("animate",true),intent.getLongExtra("time",android.os.SystemClock.uptimeMillis()),intent.getIntExtra("phase",0),intent.getLongExtra("clock_wake",0),intent.getFloatExtra("vivo_night_level",Float.NaN),intent.getFloatExtra("vivo_aod_mask",Float.NaN));
        }};
        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);renderer=new RenderLoop(CleanWallpaper.this,isPreview()?SceneOptions.DRAFT:SceneOptions.APPLIED);
            readWallpaperDim();
            // AOSP night mode is delivered through ACTION_CONFIGURATION_CHANGED,
            // already handled by the state receiver above. No OEM Secure setting.
            renderer.visible(false);
            displayManager=getSystemService(android.hardware.display.DisplayManager.class);
            displayManager.registerDisplayListener(displays,new android.os.Handler(android.os.Looper.getMainLooper()));
            if(!isPreview())SceneChannel.add(this);
            IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            // This device's DozeService keeps the display at STATE_ON with a
            // doze brightness policy, so the STATE_DOZE fallback in
            // refreshVisibility() never fires. Dreaming broadcasts are the
            // reliable doze entry/exit signal across AOSP variants.
            f.addAction(Intent.ACTION_DREAMING_STARTED);
            f.addAction(Intent.ACTION_DREAMING_STOPPED);
            IntentFilter bridge=new IntentFilter(AospSceneLink.ACTION);
            bridge.addAction(AospSceneLink.ACTION_LAYOUT);
            // SystemUI holds STATUS_BAR. Require it so ordinary third-party apps
            // cannot inject scene changes into the wallpaper engine.
            if(android.os.Build.VERSION.SDK_INT>=33){
                registerReceiver(state,f,Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(scenes,bridge,AospSceneLink.SENDER_PERMISSION,null,Context.RECEIVER_EXPORTED);
            }else{
                registerReceiver(state,f);
                registerReceiver(scenes,bridge,AospSceneLink.SENDER_PERMISSION,null);
            }
            if(!isPreview())try{
                getContentResolver().registerContentObserver(android.provider.Settings.System.getUriFor("alive_wallpaper_position"),false,area);
                regionObserverRegistered=true;
            }catch(RuntimeException error){android.util.Log.w("AliveClean","AOD region observer unavailable",error);}
            setTouchEventsEnabled(true);
            android.util.Log.i("AliveClean","Wallpaper engine created preview="+isPreview());
        }
        @Override public void scene(int next,boolean animate,long time,int phase,long clockToken,float nightLevel,float aodMask){
            boolean changed=scenesState.accept(next,time,phase);
            if(changed){ambient=next==0;renderer.mode(next,animate,nightLevel,aodMask);}
            refreshVisibility();
            if(scenesState.mode()==next&&engineDisplayId()==android.view.Display.DEFAULT_DISPLAY)renderer.clockTransition(clockToken);
            android.util.Log.i("AliveClean","Scene mode="+next+" changed="+changed+" animate="+animate+" phase="+phase+" delayMs="+(android.os.SystemClock.uptimeMillis()-time));
        }
        @Override public void region(AodRegion region){
            int display=engineDisplayId();
            if(region!=null&&region.displayId==display){receivedLayout=true;renderer.aodRegion(region,display);}
        }
        @Override public void backdrop(boolean covered){if(!isPreview()&&engineDisplayId()==android.view.Display.DEFAULT_DISPLAY)renderer.backdrop(covered);}
        @Override public void disconnected(){renderer.backdrop(false);scenesState.disconnect();update();}
        private android.view.Display engineDisplay(){
            if(android.os.Build.VERSION.SDK_INT>=30)return getDisplayContext().getDisplay();
            return getSystemService(android.hardware.display.DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY);
        }
        private int engineDisplayId(){android.view.Display display=engineDisplay();return display==null?-1:display.getDisplayId();}
        private void readOfficialRegion(){
            if(isPreview()||renderer==null||receivedLayout)return;
            try{
                android.view.Display display=engineDisplay();
                // Flyme's legacy setting contains no display identity; use it only on the main display.
                if(display==null||display.getDisplayId()!=android.view.Display.DEFAULT_DISPLAY)return;
                android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
                AodRegion region=AodRegion.parse(display.getDisplayId(),size.x,size.y,android.provider.Settings.System.getString(getContentResolver(),"alive_wallpaper_position"));
                if(region!=null)renderer.aodRegion(region,display.getDisplayId());
            }catch(RuntimeException error){android.util.Log.w("AliveClean","AOD region unavailable",error);}
        }
        private void update(){KeyguardManager k=getSystemService(KeyguardManager.class);renderer.mode(isPreview()?1:scenesState.fallback(ambient,k.isKeyguardLocked()));}
        private void readWallpaperDim(){
            boolean night=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            // LineageOS has no OEM dark-wallpaper Secure setting; the
            // platform night-mode flag is the single source of truth here.
            renderer.vivoDarkWallpaper(night);
        }
        @Override public void onSurfaceChanged(SurfaceHolder h,int format,int w,int height){super.onSurfaceChanged(h,format,w,height);{if(Diagnostics.TRACE)android.util.Log.i("AliveClean","Surface changed "+w+"x"+height);}update();renderer.attach(h.getSurface(),w,height);readOfficialRegion();}
        private void refreshVisibility(){
            android.view.Display display=engineDisplay();
            int state=display==null?android.view.Display.STATE_UNKNOWN:display.getState();
            // Doze is the AOSP AOD state. SCREEN_OFF broadcasts can be delayed
            // or dropped while the wallpaper process is frozen, so use the
            // display state itself as a second, authoritative trigger for mode 0.
            if(!isPreview()&&(state==android.view.Display.STATE_DOZE||state==android.view.Display.STATE_DOZE_SUSPEND)
                    &&scenesState.mode()!=0)scene(0,true,android.os.SystemClock.uptimeMillis(),1,0,Float.NaN,Float.NaN);
            boolean next=wallpaperVisible&&state!=android.view.Display.STATE_UNKNOWN&&state!=android.view.Display.STATE_OFF;
            boolean displayChanged=state!=lastDisplayState;
            lastDisplayState=state;
            // The decisive evidence for a blank AOD: if SystemUI hides the
            // wallpaper window while DozeService is up, renderer.visible(false)
            // kills the picture even though mode 0 is correct. Log only on a
            // real change so a 10s doze does not flood the buffer.
            if(next!=renderVisible||displayChanged){
                android.util.Log.i("AliveClean","Visibility: wallpaperVisible="+wallpaperVisible
                        +" displayState="+state+" render="+next+" (was "+renderVisible+")"
                        +" mode="+scenesState.mode());
            }
            if(next!=renderVisible){renderVisible=next;renderer.visible(next);}
            else if(next&&displayChanged)renderer.resumeDisplay();
        }
        @Override public void onVisibilityChanged(boolean visible){
            android.util.Log.i("AliveClean","Wallpaper visible="+visible);
            wallpaperVisible=visible;
            if(visible){
                update();
                // Fingerprint / face unlock straight to the launcher often
                // never delivers ACTION_USER_PRESENT (or delivers it after the
                // wallpaper window is already visible again), leaving the
                // desktop stuck in mode 1. Complete the transition ourselves
                // as soon as the keyguard is actually gone.
                if(!isPreview()&&scenesState.mode()==1){
                    KeyguardManager k=getSystemService(KeyguardManager.class);
                    if(k!=null&&!k.isKeyguardLocked()){
                        android.util.Log.i("AliveClean","Unlock fallback: keyguard gone -> mode 2");
                        scene(2,true,android.os.SystemClock.uptimeMillis(),3,0,Float.NaN,Float.NaN);
                    }
                }
            }
            refreshVisibility();
        }
        // Framework @SystemApi callback: absent from the public SDK's stubs.
        public void onAmbientModeChanged(boolean inAmbientMode,long duration){ambient=inAmbientMode;{if(Diagnostics.TRACE)android.util.Log.i("AliveClean","Ambient="+ambient);}update();refreshVisibility();}
        @Override public Bundle onCommand(String action,int x,int y,int z,Bundle extras,boolean resultRequested){
            {if(Diagnostics.TRACE)android.util.Log.i("AliveClean","Wallpaper command="+action+" authority="+scenesState.authoritative());}
            if(scenesState.authoritative())return super.onCommand(action,x,y,z,extras,resultRequested);
            boolean animate=extras==null||!extras.getBoolean("action_alive_animator_end",false);
            if("action_alive_aod".equals(action)){ambient=true;renderer.mode(0,animate);}
            else if("action_alive_lock".equals(action)||"android.wallpaper.keyguard_locked".equals(action)){ambient=false;renderer.mode(1,animate);}
            else if("action_alive_launcher".equals(action)||"android.wallpaper.keyguard_going_away".equals(action)){ambient=false;renderer.mode(2,animate);}
            return super.onCommand(action,x,y,z,extras,resultRequested);
        }
        @Override public void onTouchEvent(android.view.MotionEvent event){
            android.graphics.Rect frame=getSurfaceHolder().getSurfaceFrame();
            if(renderer!=null&&frame.width()>0&&frame.height()>0)renderer.touch(event.getActionMasked(),event.getX()/frame.width(),event.getY()/frame.height());
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder h){renderer.detach();super.onSurfaceDestroyed(h);}
        @Override public void onDestroy(){SceneChannel.remove(this);displayManager.unregisterDisplayListener(displays);unregisterReceiver(state);unregisterReceiver(scenes);if(regionObserverRegistered)getContentResolver().unregisterContentObserver(area);renderer.close();super.onDestroy();}
    }
}
