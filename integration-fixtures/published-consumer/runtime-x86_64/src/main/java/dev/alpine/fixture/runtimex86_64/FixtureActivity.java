package dev.alpine.fixture.runtimex86_64;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory;
import dev.alpine.runtime.pack.x8664.Alpine321X8664Pack;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DefaultAndroidAlpineRuntimeFactory factory = new DefaultAndroidAlpineRuntimeFactory();
        TextView text = new TextView(this);
        text.setText("runtime-x86_64:" + factory.getClass().getSimpleName() + ":"
            + Alpine321X8664Pack.create().getManifest().getRuntimeVersion());
        setContentView(text);
    }
}
