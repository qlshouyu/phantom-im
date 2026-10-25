package im.phantom.media.spi;

/** 媒体对象存储 SPI；生产实现可替换为对象存储适配器。 */
public interface MediaStorage {

    void write(String storageKey, byte[] content);

    byte[] read(String storageKey);

    boolean exists(String storageKey);

    void delete(String storageKey);
}
