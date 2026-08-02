package dev.alpine.fixture.runtimeplayworkspace;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import dev.alpine.runtime.artifact.play.PlayAssetRuntimePack;
import dev.alpine.workspace.android.AppPrivateWorkspaceStore;

public final class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppPrivateWorkspaceStore workspace = new AppPrivateWorkspaceStore(this);
        TextView text = new TextView(this);
        text.setText("play-workspace:" + PlayAssetRuntimePack.class.getSimpleName()
            + ":" + workspace.getLimits().getMaxReadBytes());
        setContentView(text);
    }
}
