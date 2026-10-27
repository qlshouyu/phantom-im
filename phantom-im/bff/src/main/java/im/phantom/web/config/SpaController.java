package im.phantom.web.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 生产部署时由 BFF 提供 Web UI 的 SPA 入口。 */
@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/register"})
    public String index() {
        return "forward:/index.html";
    }
}
