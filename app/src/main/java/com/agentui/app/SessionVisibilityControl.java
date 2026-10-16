package com.agentui.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import static com.agentui.app.Widgets.*;

/** Shared compact control; preference changes apply immediately, independently of server settings. */
final class SessionVisibilityControl extends LinearLayout {
    private final Prefs prefs;
    private final Runnable onChange;
    private final SeekBar slider;
    private final EditText number;
    private boolean painting;

    SessionVisibilityControl(Context context, Prefs prefs, Runnable onChange) {
        super(context);
        this.prefs = prefs;
        this.onChange = onChange;
        setOrientation(VERTICAL);
        int pad = Theme.dp(context, 16);
        setPadding(pad, Theme.dp(context, 8), pad, 0);
        setBackgroundColor(Theme.BG);
        LinearLayout row = Widgets.row(context);
        slider = new SeekBar(context);
        slider.setLayoutParams(lp(0, Theme.dp(context, 40), 1f));
        slider.setContentDescription("Session inactivity cutoff in days; last tick shows all sessions");
        slider.setMax(SessionAgeSteps.ALL);
        // Extend only the track artwork into the existing horizontal padding.
        // The SeekBar bounds (and thus its ticks, thumb and touch mapping) stay intact.
        Drawable track = slider.getProgressDrawable();
        if (track instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) track.mutate();
            int overhang = Theme.dp(context, 4);
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                layers.setLayerInsetLeft(i, -overhang);
                layers.setLayerInsetRight(i, -overhang);
            }
        }
        slider.setProgressTintList(ColorStateList.valueOf(Theme.ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.LINE));
        slider.setThumbTintList(ColorStateList.valueOf(Theme.ACCENT));
        slider.setTickMark(context.getDrawable(R.drawable.seekbar_tick_split));
        slider.setTickMarkTintList(ColorStateList.valueOf(Theme.MUTED));
        row.addView(slider);
        number = Widgets.field(context, "", InputType.TYPE_CLASS_NUMBER, true);
        number.setMinHeight(0);
        number.setMinimumHeight(0);
        number.setPadding(Theme.dp(context, 6), 0, Theme.dp(context, 6), 0);
        number.setTextSize(14);
        int numberWidth = (int) Math.ceil(number.getPaint().measureText("000"))
                + number.getPaddingLeft() + number.getPaddingRight();
        number.setLayoutParams(lp(numberWidth, Theme.dp(context, 32)));
        number.setGravity(android.view.Gravity.CENTER);
        number.setSingleLine(true);
        number.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        number.setContentDescription("Exact session inactivity cutoff in days; infinity shows all sessions");
        number.setImeOptions(EditorInfo.IME_ACTION_DONE);
        row.addView(number);
        addView(row);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && !painting) selectStep(value);
            }
            public void onStartTrackingTouch(SeekBar bar) { number.clearFocus(); }
            public void onStopTrackingTouch(SeekBar bar) {
                // A tap on the current tick may not dispatch onProgressChanged. It must
                // still replace an exact typed cutoff with the selected preset (or infinity).
                selectStep(bar.getProgress());
            }
        });
        number.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) {
                if (painting || s.length() == 0 || s.toString().equals("∞")) return;
                try {
                    long value = Long.parseLong(s.toString());
                    int clamped = (int) Math.max(1, Math.min(Integer.MAX_VALUE, value));
                    commit(true, clamped);
                    if (value != clamped) {
                        paint(false);
                        number.setSelection(number.length());
                    }
                } catch (NumberFormatException ignored) {
                    // Incomplete/invalid input is restored when editing finishes.
                }
            }
        });
        number.setOnFocusChangeListener((v, focused) -> {
            if (focused && !prefs.sessionAgeEnabled()) {
                painting = true;
                number.setText("");
                painting = false;
            } else if (!focused) paint(false);
        });
        number.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            number.clearFocus();
            ((android.view.inputmethod.InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(number.getWindowToken(), 0);
            return true;
        });
        paint(false);
    }

    Integer cutoff() { return prefs.sessionAgeEnabled() ? prefs.sessionAgeDays() : null; }

    void refresh() { paint(true); }

    private void selectStep(int position) {
        boolean enabled = position != SessionAgeSteps.ALL;
        commit(enabled, enabled ? SessionAgeSteps.days(position) : prefs.sessionAgeDays());
    }

    private void commit(boolean enabled, int days) {
        boolean changed = enabled != prefs.sessionAgeEnabled() || days != prefs.sessionAgeDays();
        if (changed) prefs.saveSessionAge(enabled, days);
        paint(true);
        if (changed) onChange.run();
    }

    private void paint(boolean preserveEdit) {
        painting = true;
        slider.setProgress(SessionAgeSteps.position(cutoff()));
        if (!preserveEdit || !number.hasFocus()) {
            number.setText(prefs.sessionAgeEnabled() ? String.valueOf(prefs.sessionAgeDays()) : "∞");
        }
        painting = false;
    }
}
