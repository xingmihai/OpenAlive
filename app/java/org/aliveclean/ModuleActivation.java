package org.aliveclean;

import android.content.*;
import android.net.Uri;
import android.os.*;

/** A live SystemUI module receipt, separate from optional clock capabilities. */
final class ModuleActivation {
    static final String QUERY="org.aliveclean.QUERY_MODULE_ACTIVATION";
    private static final Uri STATUS=Uri.parse("content://org.aliveclean.clock.runtime");
    private static final Binder OWNER=new Binder();
    private static final java.util.concurrent.atomic.AtomicBoolean announcing=new java.util.concurrent.atomic.AtomicBoolean();
    static void announce(Context context){
        if(!announcing.compareAndSet(false,true))return;
        new Thread(()->{
            try{
                // The app's ContentProvider may not be instantiated yet when
                // SystemUI starts at boot. A single call() returning null used
                // to lose the receipt for the whole session. Retry briefly;
                // the home screen QUERY and the boot/screen-on receivers can
                // also trigger a fresh announce at any time.
                for(int attempt=0;attempt<12;attempt++){
                    Bundle data=new Bundle();data.putBinder("owner",OWNER);
                    Bundle reply=context.getContentResolver().call(STATUS,"hookAnnounce",null,data);
                    if(reply!=null)break;
                    try{Thread.sleep(500);}catch(InterruptedException interrupted){
                        Thread.currentThread().interrupt();break;
                    }
                }
            }catch(RuntimeException unavailable){
                // Provider not reachable; a later QUERY will try again.
            }finally{announcing.set(false);}
        },"OpenAliveModuleReceipt").start();
    }

    static void request(Context context){
        try{context.sendBroadcast(new Intent(QUERY).setPackage("com.android.systemui"));}
        catch(RuntimeException unavailable){android.util.Log.w("OpenAliveModule","SystemUI activation query unavailable",unavailable);}
    }
    static boolean active(Context context){
        try{
            Bundle state=context.getContentResolver().call(STATUS,"hookStatus",null,null);
            return state!=null&&state.getBoolean("active");
        }catch(RuntimeException unavailable){return false;}
    }
    private ModuleActivation(){}
}
