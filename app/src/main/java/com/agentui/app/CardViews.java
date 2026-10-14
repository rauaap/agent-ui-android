package com.agentui.app;

import android.view.View;
import android.widget.LinearLayout;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reconcile attached rows without detaching cards for ordinary content refreshes. */
final class CardViews {
    private CardViews() {}

    interface Rows<C> {
        int size();
        C get(int index);
        void remove(int index);
        void add(int index, C row);
    }

    static void reconcile(LinearLayout container, List<? extends View> rows) {
        reconcile(new Rows<View>() {
            public int size() { return container.getChildCount(); }
            public View get(int index) { return container.getChildAt(index); }
            public void remove(int index) { container.removeViewAt(index); }
            public void add(int index, View row) { container.addView(row, index); }
        }, rows);
    }

    /** Also exercised with a fake container to verify attachment operations in unit tests. */
    static <C> void reconcile(Rows<C> container, List<? extends C> rows) {
        Set<C> retained = new HashSet<>(rows);
        for (int i = container.size() - 1; i >= 0; i--) {
            if (!retained.contains(container.get(i))) container.remove(i);
        }
        for (int i = 0; i < rows.size(); i++) {
            C row = rows.get(i);
            if (i < container.size() && container.get(i) == row) continue;
            for (int j = i + 1; j < container.size(); j++) {
                if (container.get(j) == row) { container.remove(j); break; }
            }
            container.add(i, row);
        }
    }
}
