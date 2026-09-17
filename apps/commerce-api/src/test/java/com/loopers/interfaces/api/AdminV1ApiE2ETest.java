package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminV1ApiE2ETest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("관리자 브랜드 API는, ")
    @Nested
    class Brands {
        @DisplayName("관리자가 요청하면, 브랜드를 생성·수정·삭제할 수 있다.")
        @Test
        void allowsCrud_whenRequestedByAdmin() throws Exception {
            // create
            String createBody = objectMapper.writeValueAsString(Map.of("name", "나이키"));
            String createResponse = mvc.perform(post("/api-admin/v1/brands")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("나이키"))
                .andReturn().getResponse().getContentAsString();

            Long brandId = objectMapper.readTree(createResponse).path("data").path("id").asLong();

            // update
            String updateBody = objectMapper.writeValueAsString(Map.of("name", "아디다스"));
            mvc.perform(put("/api-admin/v1/brands/" + brandId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("아디다스"));

            // delete
            mvc.perform(delete("/api-admin/v1/brands/" + brandId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());

            BrandModel deleted = brandJpaRepository.findById(brandId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }

        @DisplayName("일반 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByNonAdminUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByUnauthenticatedUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands"))
                .andExpect(status().isForbidden());
        }
    }
}
