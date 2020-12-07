package com.projects.shiftproscheduler.optimizer;

import com.skaggsm.ortools.OrToolsHelper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverSolutionCallback;
import com.google.ortools.sat.CpSolverStatus;
import com.projects.shiftproscheduler.assignment.Assignment;
import com.projects.shiftproscheduler.assignment.AssignmentRepository;
import com.projects.shiftproscheduler.constraint.DefaultConstraintService;
import com.projects.shiftproscheduler.employee.Employee;
import com.projects.shiftproscheduler.employee.EmployeeRepository;
import com.projects.shiftproscheduler.schedule.Schedule;
import com.projects.shiftproscheduler.shift.Shift;
import com.projects.shiftproscheduler.shift.ShiftRepository;

@Service
public class DefaultOptimizer implements IOptimizer {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    ShiftRepository shiftRepository;

    @Autowired
    AssignmentRepository assignmentRepository;

    Logger logger = LoggerFactory.getLogger(DefaultOptimizer.class);

    private final DefaultConstraintService constraintService;

    public DefaultOptimizer(DefaultConstraintService constraintService) {
        OrToolsHelper.loadLibrary(); // Load Google OR Tools per maven spec
        this.constraintService = constraintService;
    }

    public Collection<Assignment> generateSchedule(Schedule schedule) throws IllegalStateException {

        CpModel model = new CpModel(); // Init model
        Collection<Employee> employees = employeeRepository.findAll();
        Collection<Shift> shifts = shiftRepository.findAll();

        // Create shift variables
        // shift_e_d_s: employee 'e' works shift 's' on day 'd'
        HashMap<String, IntVar> shiftVars = new HashMap<String, IntVar>();
        for (Employee employee : employees) {
            for (int d = 0; d < schedule.getDays(); d++) {
                for (Shift shift : shifts) {
                    shiftVars.put(String.format("%d, %d, %d", employee.getId(), d, shift.getId()),
                            model.newBoolVar(String.format("shift_%d_%d_%d", employee.getId(), d, shift.getId())));
                }
            }
        }

        // Each shift assigned exactly MIN_EMPLOYEES per period
        for (int d = 0; d < schedule.getDays(); d++) {
            for (Shift shift : shifts) {
                ArrayList<IntVar> localVars = new ArrayList<IntVar>();
                for (Employee employee : employees) {
                    IntVar shiftVar = shiftVars
                            .getOrDefault(String.format("%d, %d, %d", employee.getId(), d, shift.getId()), null);
                    if (shiftVar != null)
                        localVars.add(shiftVar);
                }
                model.addEquality(LinearExpr.sum(localVars.toArray(new IntVar[0])),
                        constraintService.getMinEmployeesPerShift());
            }
        }

        // Each employee works at most MAX_SHIFTS per day
        for (Employee employee : employees) {
            for (int d = 0; d < schedule.getDays(); d++) {
                ArrayList<IntVar> localVars = new ArrayList<IntVar>();
                for (Shift shift : shifts) {
                    IntVar shiftVar = shiftVars
                            .getOrDefault(String.format("%d, %d, %d", employee.getId(), d, shift.getId()), null);
                    if (shiftVar != null)
                        localVars.add(shiftVar);
                }
                model.addLessOrEqual(LinearExpr.sum(localVars.toArray(new IntVar[0])),
                        constraintService.getMaxShiftsPerEmployee());
            }
        }

        // Distribute shifts evenly by default if possible
        int minShiftsPerEmployee = Math.floorDiv((shifts.size() * schedule.getDays()),
                employees.size());
        int maxShiftsPerEmployee = 0;

        if (shifts.size() * schedule.getDays() % employees.size() == 0) {
            maxShiftsPerEmployee = minShiftsPerEmployee;
        } else {
            maxShiftsPerEmployee = minShiftsPerEmployee + 1;
        }
        for (Employee employee : employees) {
            ArrayList<IntVar> localVars = new ArrayList<IntVar>();
            for (int d = 0; d < schedule.getDays(); d++) {
                for (Shift shift : shifts) {
                    IntVar shiftVar = shiftVars.get(String.format("%d, %d, %d", employee.getId(), d, shift.getId()));
                    if (shiftVar != null)
                        localVars.add(shiftVar);
                }
            }
            model.addLessOrEqual(model.newConstant(minShiftsPerEmployee),
                    LinearExpr.sum(localVars.toArray(new IntVar[0])));
            model.addLessOrEqual(LinearExpr.sum(localVars.toArray(new IntVar[0])), maxShiftsPerEmployee);
        }

        // Solve
        CpSolver solver = new CpSolver();
        CpSolverStatus status = solver.solve(model);

        // Create the schedule and assignments
        Collection<Assignment> assignments = new ArrayList<Assignment>();

        if (status == CpSolverStatus.FEASIBLE || status == CpSolverStatus.OPTIMAL) {
            // TODO: Determine if random selection of feasible/optimal solutions used
            for (IntVar v : shiftVars.values().toArray(new IntVar[0])) { // Get first optimal solution
                String[] varValues = v.getName().split("_"); // Get constraint var values
                if (solver.value(v) == 1) {
                    // Get assignment values
                    Optional<Employee> employeeUser = employeeRepository.findById(Integer.parseInt(varValues[1]));
                    Employee emp = employeeUser.orElseThrow();
                    int dayId = Integer.parseInt(varValues[2]);
                    Shift shift = shiftRepository.findById(Integer.parseInt(varValues[3]));

                    Assignment assignment = new Assignment();
                    assignment.setEmployee(emp);
                    assignment.setDayId(dayId);
                    assignment.setShift(shift);
                    assignment.setSchedule(schedule);
                    assignments.add(assignment);
                }
            }
        } else {
            logger.error("Optmizer feasibility invalid for group of employees");
            throw new IllegalStateException("Model feasibility invalid");
        }
        return assignments;
    }

    // Debugging
    // VarArraySolutionPrinter cb = new
    // VarArraySolutionPrinter(shiftVars.values().toArray(new IntVar[0]));
    // solver.searchAllSolutions(model, cb);
    // System.out.println(cb.solutionCount);
    static class VarArraySolutionPrinter extends CpSolverSolutionCallback {

        private int solutionCount;
        private final IntVar[] variableArray;

        public VarArraySolutionPrinter(IntVar[] variables) {
            variableArray = variables;
        }

        @Override
        public void onSolutionCallback() {
            System.out.printf("Solution #%d: time = %.02f s%n", solutionCount, wallTime());
            for (IntVar v : variableArray) {
                System.out.printf(" %s = %d%n", v.getName(), value(v));
            }

            solutionCount++;
        }

        public int getSolutionCount() {
            return solutionCount;
        }
    }
}