package org.triplea.lobby.server.db;

import static com.google.common.base.Preconditions.checkNotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.triplea.lobby.server.User;
import org.triplea.util.Tuple;

import lombok.AllArgsConstructor;

/**
 * Utility class to create/read/delete banned macs (there is no update).
 */
@AllArgsConstructor
class BannedMacController implements BannedMacDao {

  private final Supplier<Connection> connection;

  @Override
  public void addBannedMac(final User bannedUser, final @Nullable Instant banTill, final User moderator) {
    checkNotNull(bannedUser);
    checkNotNull(moderator);

    if (banTill != null && banTill.isBefore(Instant.now())) {
      removeBannedMac(bannedUser.getHashedMacAddress());
      return;
    }

    final String sql = ""
        + "insert into banned_macs "
        + "  (username, ip, mac, ban_till, mod_username, mod_ip, mod_mac) values (?, ?::inet, ?, ?, ?, ?::inet, ?) "
        + "on conflict (mac) do update set "
        + "  ban_till=excluded.ban_till, "
        + "  username=excluded.username, "
        + "  ip=excluded.ip, "
        + "  mod_username=excluded.mod_username, "
        + "  mod_ip=excluded.mod_ip, "
        + "  mod_mac=excluded.mod_mac";
    try (Connection con = connection.get();
        PreparedStatement ps = con.prepareStatement(sql)) {
      ps.setString(1, bannedUser.getUsername());
      ps.setString(2, bannedUser.getInetAddress().getHostAddress());
      ps.setString(3, bannedUser.getHashedMacAddress());
      ps.setTimestamp(4, banTill != null ? Timestamp.from(banTill) : null);
      ps.setString(5, moderator.getUsername());
      ps.setString(6, moderator.getInetAddress().getHostAddress());
      ps.setString(7, moderator.getHashedMacAddress());
      ps.execute();
      con.commit();
    } catch (final SQLException e) {
      throw new DatabaseException("Error inserting banned mac: " + bannedUser.getHashedMacAddress(), e);
    }
  }

  private void removeBannedMac(final String mac) {
    try (Connection con = connection.get();
        PreparedStatement ps = con.prepareStatement("delete from banned_macs where mac=?")) {
      ps.setString(1, mac);
      ps.execute();
      con.commit();
    } catch (final SQLException e) {
      throw new DatabaseException("Error deleting banned mac: " + mac, e);
    }
  }

  /**
   * This implementation has the side effect of removing any MACs whose ban has expired.
   */
  @Override
  public Tuple<Boolean, /* @Nullable */ Timestamp> isMacBanned(final Instant nowtime, final String mac) {
    final String sql = "select mac, ban_till from banned_macs where mac=?";

    try (Connection con = connection.get();
        PreparedStatement ps = con.prepareStatement(sql)) {
      ps.setString(1, mac);
      try (ResultSet rs = ps.executeQuery()) {
        // If the ban has expired, allow the mac
        if (rs.next()) {
          final Timestamp banTill = rs.getTimestamp(2);
          if (banTill != null && banTill.toInstant().isBefore(nowtime)) {
            removeBannedMac(mac);
            return Tuple.of(false, banTill);
          }
          return Tuple.of(true, banTill);
        }
        return Tuple.of(false, null);
      }
    } catch (final SQLException e) {
      throw new DatabaseException("Error for testing banned mac existence: " + mac, e);
    }
  }
}
