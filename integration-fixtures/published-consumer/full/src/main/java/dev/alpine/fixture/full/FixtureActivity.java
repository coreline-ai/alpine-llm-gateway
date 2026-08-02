package dev.alpine.fixture.full;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.chat.routing.ChatExecutionMode;
import dev.alpine.runtime.android.DefaultAndroidAlpineRuntimeFactory;
import dev.alpine.runtime.ui.compose.RuntimePackageInput;
import java.util.Collections;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DefaultAndroidAlpineRuntimeFactory factory = new DefaultAndroidAlpineRuntimeFactory();
        RuntimePackageInput input = new RuntimePackageInput(Collections.singletonList("git"), true);
        TextView text = new TextView(this);
        text.setText("full:" + ChatExecutionMode.FAST_CHAT.name() + ":" + factory.getClass().getSimpleName() + ":" + input.getValid());
        setContentView(text);
    }
}
