package jfrweb.web;

import java.net.InetSocketAddress;

import flak.BeforeHook;
import flak.HttpException;
import flak.Request;

/**
 * Refuses the clients that are not on this machine: the default guard, when
 * the application configures none. A reverse proxy running on this machine is
 * local too, and whatever it forwards would be accepted.
 */
public final class LoopbackOnly implements BeforeHook {

  @Override
  public void execute(Request request) {
    InetSocketAddress remote = request.getRemoteAddress();
    if (remote == null || remote.getAddress() == null || !remote.getAddress().isLoopbackAddress())
      throw new HttpException(403, "jfr-web only accepts local clients unless a guard is configured");
  }
}
