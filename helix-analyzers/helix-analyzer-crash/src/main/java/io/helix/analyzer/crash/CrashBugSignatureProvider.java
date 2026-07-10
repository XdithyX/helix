package io.helix.analyzer.crash;

import java.util.List;

public interface CrashBugSignatureProvider {
  List<CrashBugSignature> signatures();
}
