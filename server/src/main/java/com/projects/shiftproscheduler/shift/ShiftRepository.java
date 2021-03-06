package com.projects.shiftproscheduler.shift;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

public interface ShiftRepository extends Repository<Shift, Integer> {

    /**
     * Retrieve all <code>Shift</code>s from the data store.
     *
     * @return a <code>Collection</code> of <code>Shift</code>s
     */
    @Transactional(readOnly = true)
    @Cacheable("shifts")
    Collection<Shift> findAll() throws DataAccessException;

    /**
     * Find a {@link Shift} by id
     * 
     */
    @Transactional(readOnly = true)
    Shift findById(Integer id);

    /**
     * Save a {@link Shift} to the data store, either inserting or updating
     * 
     * @param shift the {@link Shift} tp save
     */
    void save(Shift shift);

}
