package com.agentui.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static com.agentui.app.Widgets.MATCH;
import static com.agentui.app.Widgets.WRAP;
import static com.agentui.app.Widgets.lp;

/** Per-project settings, reached from the gear on a project's session list. */
public class ProjectSettingsActivity extends Activity {
    static final String EXTRA_PROJECT_DIR = "project_dir";
    static final String EXTRA_PROJECT_NAME = "project_name";

    private String projectDir;
    private String projectName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectDir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        projectName = getIntent().getStringExtra(EXTRA_PROJECT_NAME);
        if (projectName == null && projectDir != null) {
            projectName = ProjectListActivity.basename(projectDir);
        }
        setContentView(buildRoot());
    }

    private View buildRoot() {
        LinearLayout root = Widgets.column(this);
        root.setBackgroundColor(Theme.BG);
        root.setLayoutParams(lp(MATCH, MATCH));
        Widgets.fitSystemWindows(root);

        LinearLayout header = Widgets.row(this);
        int pad = Theme.dp(this, 16);
        header.setPadding(pad, pad, pad, pad);
        TextView back = Widgets.ghostButton(this, "‹");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setLayoutParams(lp(Theme.dp(this, 44), Theme.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        Widgets.margins(back, 0, 0, Theme.dp(this, 12), 0);
        header.addView(back);
        header.addView(Widgets.text(this, "Project settings", Theme.INK, 18, true));
        root.addView(header);

        View div = new View(this);
        div.setLayoutParams(lp(MATCH, Math.max(1, Theme.dp(this, 0.5f))));
        div.setBackgroundColor(Theme.LINE_SOFT);
        root.addView(div);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(lp(MATCH, 0, 1f));
        LinearLayout form = Widgets.column(this);
        form.setPadding(pad, pad, pad, pad);
        TextView section = Widgets.text(this, "Project", Theme.ACCENT_STRONG, 12, true);
        section.setAllCaps(true);
        section.setLetterSpacing(0.06f);
        form.addView(section);
        form.addView(spacer(12));
        form.addView(Widgets.text(this, projectName == null ? "(unnamed)" : projectName,
                Theme.INK, 16, true));
        if (projectDir != null) {
            TextView path = Widgets.mono(this, projectDir, Theme.FAINT, 12);
            Widgets.margins(path, 0, Theme.dp(this, 6), 0, 0);
            form.addView(path);
        }

        form.addView(spacer(28));
        TextView sandboxPaths = Widgets.ghostButton(this, "Project sandbox paths ›");
        sandboxPaths.setEnabled(projectDir != null);
        sandboxPaths.setOnClickListener(v -> startActivity(new Intent(this, SandboxPathsActivity.class)
                .putExtra(SandboxPathsActivity.EXTRA_PROJECT_PATH, projectDir)));
        form.addView(sandboxPaths);
        form.addView(spacer(12));
        TextView sandboxNetwork = Widgets.ghostButton(this, "Project sandbox network ›");
        sandboxNetwork.setEnabled(projectDir != null);
        sandboxNetwork.setOnClickListener(v -> startActivity(new Intent(this, SandboxNetworkActivity.class)
                .putExtra(SandboxNetworkActivity.EXTRA_PROJECT_PATH, projectDir)));
        form.addView(sandboxNetwork);
        form.addView(spacer(12));
        TextView sharedAssets = Widgets.ghostButton(this, "Project shared assets ›");
        sharedAssets.setEnabled(projectDir != null);
        sharedAssets.setOnClickListener(v -> startActivity(new Intent(this, SharedAssetsActivity.class)
                .putExtra(SharedAssetsActivity.EXTRA_PROJECT_PATH, projectDir)));
        form.addView(sharedAssets);
        scroll.addView(form);
        root.addView(scroll);
        return root;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(lp(MATCH, Theme.dp(this, dp)));
        return v;
    }
}
