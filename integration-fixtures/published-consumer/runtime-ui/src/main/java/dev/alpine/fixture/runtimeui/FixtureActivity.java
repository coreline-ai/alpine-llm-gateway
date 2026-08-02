package dev.alpine.fixture.runtimeui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.runtime.ui.compose.RuntimePackageInput;
import java.util.Collections;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RuntimePackageInput input = new RuntimePackageInput(Collections.singletonList("git"), true);
        TextView text = new TextView(this);
        text.setText("runtime-ui:" + input.getPackages().get(0));
        setContentView(text);
    }
}
