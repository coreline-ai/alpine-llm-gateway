package dev.alpine.fixture.runtimeonly;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory;
import dev.alpine.runtime.api.RuntimeInstallRequest;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DefaultAndroidAlpineRuntimeFactory factory = new DefaultAndroidAlpineRuntimeFactory();
        RuntimeInstallRequest request = new RuntimeInstallRequest();
        TextView text = new TextView(this);
        text.setText("runtime-only:" + factory.getClass().getSimpleName() + ":" + request.getForceReinstall());
        setContentView(text);
    }
}
