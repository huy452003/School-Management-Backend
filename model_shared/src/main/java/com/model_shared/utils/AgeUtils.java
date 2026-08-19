package com.model_shared.utils;

import java.time.LocalDate;
import java.time.Period;

public class AgeUtils {

    public static Integer calculateAge(LocalDate birth) {
        if (birth == null) {
            return null;
        }
        return Period.between(birth, LocalDate.now()).getYears();
    }

}
