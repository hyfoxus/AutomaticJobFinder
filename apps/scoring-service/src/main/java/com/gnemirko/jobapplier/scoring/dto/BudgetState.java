package com.gnemirko.jobapplier.scoring.dto;

public enum BudgetState {
    /** Under 80% of the daily cap. */
    OK,
    /** At or above 80% of the cap; scoring continues. */
    NEAR_CAP,
    /** The next call would not fit; remaining rows were left unscored. */
    CAP_REACHED
}
