package io.servicecomb.poc.demo.seckill.repositories.spring;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringOutboxRepository extends JpaRepository<OutboxEntity, Long> {

  List<OutboxEntity> findTop50ByPublishedFalseOrderByIdAsc();
}
