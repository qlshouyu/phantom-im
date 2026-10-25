package im.phantom.push.spi;

/** 系统推送通道 SPI：APNs、FCM 和厂商通道通过适配器实现。 */
public interface PushProvider {

    String send(PushMessage message);
}
