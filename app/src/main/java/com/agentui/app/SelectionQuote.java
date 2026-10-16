package com.agentui.app;

import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

/** Adds Quote alongside Android's native text-selection actions in a session. */
final class SelectionQuote {
    interface Host {
        void quoteSelection(String text);
    }

    private SelectionQuote() {}

    static String format(String text) {
        return "> " + text + "\n\n";
    }

    static void install(TextView view) {
        if (!(view.getContext() instanceof Host)) return;
        Host host = (Host) view.getContext();
        view.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            private MenuItem quote;

            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                quote = menu.add("Quote");
                quote.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                quote.setVisible(view.getSelectionStart() != view.getSelectionEnd());
                return false;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item != quote) return false;
                int start = view.getSelectionStart();
                int end = view.getSelectionEnd();
                if (start < 0 || end < 0 || start == end) return true;
                String text = view.getText().subSequence(Math.min(start, end), Math.max(start, end)).toString();
                mode.finish();
                host.quoteSelection(text);
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {}
        });
    }
}
