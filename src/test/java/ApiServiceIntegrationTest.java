import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import org.example.java_code.dto.ApiResponse;
import org.example.java_code.dto.GenerationRequest;
import org.example.java_code.service.ApiService;
import org.example.java_code.service.impl.ApiServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiServiceIntegrationTest {

  private static final String API_URL =
      "https://backend.dibrain.data-infra.live-test.shopee.io/group_intro/generate/invoke";

  private ApiService apiService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    RestTemplate restTemplate = new RestTemplate();
    apiService = new ApiServiceImpl(restTemplate);
    ReflectionTestUtils.setField(apiService, "apiUrl", API_URL);
  }

  @Test
  void generateIntroduction_callsRealApi() throws Exception {
    GenerationRequest request = buildRequest();

    ApiResponse response = apiService.generateIntroduction(request);

    assertNotNull(response, "API 响应为空");
    if (response.getOutput() != null) {
      System.out.println("API introduction: " + response);
    } else {
      System.out.println(objectMapper.writeValueAsString(response));
    }
  }

  private GenerationRequest buildRequest() {
    GenerationRequest request = new GenerationRequest();

    GenerationRequest.ConfigDTO configDTO = new GenerationRequest.ConfigDTO();
    GenerationRequest.ConfigDTO.MetadataDTO metadataDTO = new GenerationRequest.ConfigDTO.MetadataDTO();
    metadataDTO.setReg("SG");
    metadataDTO.setUserEmail("xinbo.wang");
    configDTO.setMetadata(metadataDTO);
    request.setConfig(configDTO);

    GenerationRequest.InputDTO inputDTO = new GenerationRequest.InputDTO();
    inputDTO.setTableGroupId("Order Mart");
    GenerationRequest.InputDTO.RawDocsDTO rawDocsDTO = new GenerationRequest.InputDTO.RawDocsDTO();
    rawDocsDTO.setIndexInfo("Order Mart v3 User guide");
    rawDocsDTO.setTextContent("Order Mart v3 User guide\n\n.......");
    rawDocsDTO.setTitle("FAQ");
    inputDTO.setRawDocs(Collections.singletonList(rawDocsDTO));
    request.setInput(inputDTO);

    return request;
  }
}

