package dev.alpine.fixture.noruntime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.chat.feature.backend.ChatBackendDescriptor;
import dev.alpine.llm.OAuthPkceMode;
import java.util.Collections;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ChatBackendDescriptor chat = new ChatBackendDescriptor(
            "fixture", "Fixture", "fixture-model", Collections.singletonList("fixture-model")
        );
        TextView text = new TextView(this);
        text.setText("no-runtime:" + OAuthPkceMode.STANDARD.name() + ":" + chat.getModel());
        setContentView(text);
    }
}
