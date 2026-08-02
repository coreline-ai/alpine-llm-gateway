package dev.alpine.fixture.noruntime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.llm.OAuthPkceMode;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("no-runtime:" + OAuthPkceMode.STANDARD.name());
        setContentView(text);
    }
}
