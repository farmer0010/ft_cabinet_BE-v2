package com.gyeongsan.cabinet.adapter.out.persistence.faq;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FaqJpaRepository extends JpaRepository<FaqEntity, Long> {

    @Query("SELECT DISTINCT f FROM FaqEntity f LEFT JOIN FETCH f.questions ORDER BY f.id")
    List<FaqEntity> findAllWithQuestions();

    @Query(
            "SELECT DISTINCT f FROM FaqEntity f LEFT JOIN FETCH f.questions"
                    + " WHERE f.enabled = true ORDER BY f.id")
    List<FaqEntity> findAllEnabledWithQuestions();

    @Query("SELECT f FROM FaqEntity f LEFT JOIN FETCH f.questions WHERE f.id = :id")
    Optional<FaqEntity> findByIdWithQuestions(@Param("id") Long id);

    @Query("SELECT COUNT(f), MAX(f.updatedAt) FROM FaqEntity f")
    List<Object[]> countAndLastUpdated();
}
