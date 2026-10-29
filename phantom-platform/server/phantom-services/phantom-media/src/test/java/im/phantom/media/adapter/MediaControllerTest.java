package im.phantom.media.adapter;

import im.phantom.common.http.HttpAuthAttributes;
import im.phantom.media.app.MediaAppService;
import im.phantom.media.app.MediaDtos.MediaContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ContentDisposition;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaControllerTest {
    private final MediaAppService media = mock(MediaAppService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaController(media)).build();

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "image/svg+xml", "video/mp4"})
    void servesFilesAsAttachmentsWithOriginalChineseName(String mime) throws Exception {
        byte[] bytes = "附件内容".getBytes(StandardCharsets.UTF_8);
        when(media.content("app", "u1", "m1", 123L, "sig", "original"))
                .thenReturn(new MediaContent(bytes, mime, "配置 \"示例\".json", "FILE"));
        var response = mvc.perform(get("/media/m1/content")
                        .param("expires", "123").param("signature", "sig")
                        .requestAttr(HttpAuthAttributes.APP_ID, "app")
                        .requestAttr(HttpAuthAttributes.USER_ID, "u1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(mime))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();
        ContentDisposition disposition = ContentDisposition.parse(response.getHeader("Content-Disposition"));
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("配置 \"示例\".json");
    }

    @Test
    void keepsImagePreviewInline() throws Exception {
        when(media.content("app", "u1", "m1", 123L, "sig", "thumb"))
                .thenReturn(new MediaContent(new byte[]{1, 2}, "image/jpeg", "thumbnail.jpg", "IMAGE"));
        mvc.perform(get("/media/m1/content").param("expires", "123").param("signature", "sig")
                        .param("variant", "thumb")
                        .requestAttr(HttpAuthAttributes.APP_ID, "app")
                        .requestAttr(HttpAuthAttributes.USER_ID, "u1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
