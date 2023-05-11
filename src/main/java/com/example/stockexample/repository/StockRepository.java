package com.example.stockexample.repository;

import com.example.stockexample.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import static javax.persistence.LockModeType.*;

public interface StockRepository extends JpaRepository<Stock, Long> {

    @Lock(PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.id = :id")
    Stock findByIdWithPessimisticLock(Long id);

}
