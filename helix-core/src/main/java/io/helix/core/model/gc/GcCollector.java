package io.helix.core.model.gc;

public enum GcCollector {
  G1,
  ZGC,
  SHENANDOAH,
  CMS,
  PARALLEL,
  SERIAL,
  OPENJ9,
  UNKNOWN
}
