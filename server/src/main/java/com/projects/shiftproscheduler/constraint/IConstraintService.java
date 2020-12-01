package com.projects.shiftproscheduler.constraint;

public abstract class IConstraintService {

    public abstract int getNumberOfDays();

    public abstract int getMaxShiftsPerEmployee();

    public abstract int getMinEmployeesPerShift();

}
