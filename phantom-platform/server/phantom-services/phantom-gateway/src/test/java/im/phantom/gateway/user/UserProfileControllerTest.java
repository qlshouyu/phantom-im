package im.phantom.gateway.user;

import im.phantom.api.user.UpdateUserProfileRequest;
import im.phantom.api.user.UserProfileDto;
import im.phantom.api.user.UserService;
import im.phantom.common.http.HttpAuthAttributes;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserProfileControllerTest {

    @Test
    void readsAndWritesCurrentUserWithSdkWriteEnabled() throws Exception {
        UserService users = mock(UserService.class);
        UserProfileDto profile = new UserProfileDto("u1", "app1", "Alice", "",
                Map.of("signature", "hello"), "ACTIVE");
        when(users.getProfile("app1", "u1")).thenReturn(profile);
        when(users.updateProfile(eq("app1"), eq("u1"), eq(new UpdateUserProfileRequest(
                "Alice", "", Map.of("signature", "new"))), eq(true))).thenReturn(profile);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UserProfileController(users))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
                .build();

        mvc.perform(get("/users/me")
                        .requestAttr(HttpAuthAttributes.APP_ID, "app1")
                        .requestAttr(HttpAuthAttributes.USER_ID, "u1")
                        .header("X-Request-Id", "req-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user_id").value("u1"))
                .andExpect(jsonPath("$.request_id").value("req-1"));

        mvc.perform(put("/users/me")
                        .requestAttr(HttpAuthAttributes.APP_ID, "app1")
                        .requestAttr(HttpAuthAttributes.USER_ID, "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Alice","avatar_url":"","ext":{"signature":"new"}}
                                """))
                .andExpect(status().isOk());

        verify(users).updateProfile(eq("app1"), eq("u1"), eq(new UpdateUserProfileRequest(
                "Alice", "", Map.of("signature", "new"))), eq(true));
    }
}
