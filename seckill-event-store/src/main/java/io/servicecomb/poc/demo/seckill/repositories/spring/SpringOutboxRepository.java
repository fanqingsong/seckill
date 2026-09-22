package io.servicecomb.poc.demo.seckill.repositories.spring;

import io.servicecomb.poc.demo.seckill.entities.OutboxEntity;
import java.util.List;
import org.springframework.data.repository.PagingAndSortingRepository;

public interface SpringOutboxRepository extends PagingAndSortingRepository<OutboxEntity, Long> {

  List<OutboxEntity> findTop50ByPublishedFalseOrderByIdAsc();
}
