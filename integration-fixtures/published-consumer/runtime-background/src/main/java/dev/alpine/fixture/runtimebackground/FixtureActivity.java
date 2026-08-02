package dev.alpine.fixture.runtimebackground;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.runtime.background.android.RuntimeForegroundServiceController;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RuntimeForegroundServiceController controller = new RuntimeForegroundServiceController(this);
        TextView text = new TextView(this);
        text.setText("runtime-background:" + controller.snapshot().getState().name());
        setContentView(text);
    }
}
