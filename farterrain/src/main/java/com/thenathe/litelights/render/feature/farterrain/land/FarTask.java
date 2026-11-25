package com.thenathe.litelights.render.feature.farterrain.land;

/**
 * Work for far terrain's background threads (the Background threads row), soonest first: meshes
 * by their tiles' urgency, and after every mesh waiting, seed generation's predictions
 * ({@link PredictedLand#TASK_PRIORITY}), so drawing what is known never waits on guessing more.
 */
abstract class FarTask implements Runnable, Comparable<FarTask> {
    private final double priority;

    FarTask(double priority) {
        this.priority = priority;
    }

    @Override
    public int compareTo(FarTask other) {
        return Double.compare(priority, other.priority);
    }
}
