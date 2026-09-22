package io.servicecomb.poc.demo.seckill.web;

import io.servicecomb.poc.demo.seckill.EventProjector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class ReplayController {

  private final EventProjector projector;

  public ReplayController(EventProjector projector) {
    this.projector = projector;
  }

  @PostMapping("/replay")
  public String replay(@RequestParam String promotionId, @RequestParam(defaultValue = "0") long fromSeq) {
    projector.replay(promotionId, fromSeq);
    return "replayed";
  }
}
