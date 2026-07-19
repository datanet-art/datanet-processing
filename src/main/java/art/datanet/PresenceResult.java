package art.datanet;

import java.util.List;

/** Current authoritative occupancy for one DataNet channel. */
public record PresenceResult(int occupancy, List<String> members) {
  public PresenceResult {
    members = List.copyOf(members);
  }
}
