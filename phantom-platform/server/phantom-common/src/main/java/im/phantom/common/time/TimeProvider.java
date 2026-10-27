package im.phantom.common.time;

/** 可替换时间源，业务代码不得直接依赖系统时钟以保持测试确定性。 */
@FunctionalInterface
public interface TimeProvider {

    long nowMillis();
}
