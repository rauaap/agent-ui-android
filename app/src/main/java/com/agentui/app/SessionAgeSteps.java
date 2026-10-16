package com.agentui.app;

/** Fixed slider presets; null is infinity, while exact numeric cutoffs remain independent. */
final class SessionAgeSteps {
    private static final int[] DAYS = {1, 2, 3, 4, 5, 6, 7, 14, 30, 60, 90};
    static final int ALL = DAYS.length;

    static int position(Integer days) {
        if (days != null) {
            for (int i = 0; i < DAYS.length; i++) {
                if (days <= DAYS[i]) return i;
            }
        }
        return ALL;
    }

    static Integer days(int position) {
        return position == ALL ? null : DAYS[position];
    }
}
