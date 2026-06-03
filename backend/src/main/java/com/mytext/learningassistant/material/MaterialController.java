package com.mytext.learningassistant.material;

import java.io.IOException;
import java.util.List;

import com.mytext.learningassistant.common.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService materialService;
    private final MaterialFileTicketService fileTicketService;

    public MaterialController(MaterialService materialService, MaterialFileTicketService fileTicketService) {
        this.materialService = materialService;
        this.fileTicketService = fileTicketService;
    }

    @PostMapping
    public ApiResponse<MaterialResponse> upload(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "sourceType", required = false) String sourceType,
        @RequestParam(value = "sourceUrl", required = false) String sourceUrl
    ) {
        return ApiResponse.ok(materialService.upload(currentUserId, title, sourceType, file, sourceUrl));
    }

    @PostMapping("/web")
    public ApiResponse<MaterialResponse> importWeb(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody WebMaterialRequest request
    ) {
        return ApiResponse.ok(materialService.importWeb(currentUserId, request.title(), request.sourceUrl()));
    }

    @PostMapping("/upload-sessions")
    public ApiResponse<MaterialUploadSessionResponse> createUploadSession(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody MaterialUploadSessionCreateRequest request
    ) {
        return ApiResponse.ok(materialService.createUploadSession(currentUserId, request));
    }

    @GetMapping("/upload-sessions/{sessionId}")
    public ApiResponse<MaterialUploadSessionResponse> getUploadSession(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("sessionId") String sessionId
    ) {
        return ApiResponse.ok(materialService.getUploadSession(currentUserId, sessionId));
    }

    @PostMapping("/upload-sessions/{sessionId}/chunks")
    public ApiResponse<MaterialUploadSessionResponse> uploadChunk(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("sessionId") String sessionId,
        @RequestParam("chunkIndex") int chunkIndex,
        @RequestParam("totalChunks") int totalChunks,
        @RequestParam("chunk") MultipartFile chunk,
        @RequestParam(value = "checksumSha256", required = false) String checksumSha256
    ) {
        return ApiResponse.ok(materialService.uploadChunk(currentUserId, sessionId, chunkIndex, totalChunks, chunk, checksumSha256));
    }

    @GetMapping
    public ApiResponse<List<MaterialResponse>> list(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(materialService.list(currentUserId));
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialDetailResponse> detail(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.detail(currentUserId, id));
    }

    @GetMapping("/{id}/chunks")
    public ApiResponse<List<MaterialChunkResponse>> chunks(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.chunks(currentUserId, id));
    }

    @GetMapping("/{id}/pages")
    public ApiResponse<List<MaterialPageResponse>> pages(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.pages(currentUserId, id));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<UrlResource> file(
        @RequestAttribute(value = "currentUserId", required = false) Long currentUserId,
        @PathVariable("id") long id,
        @RequestParam(value = "ticket", required = false) String ticket
    ) throws IOException {
        long ownerId = currentUserId != null ? currentUserId : requireTicketOwner(ticket, id);
        MaterialFileResource file = materialService.file(ownerId, id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .contentLength(file.contentLength())
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
            .body(new UrlResource(file.path().toUri()));
    }

    @PostMapping("/{id}/file-ticket")
    public ApiResponse<MaterialFileTicketResponse> fileTicket(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        materialService.detail(currentUserId, id);
        return ApiResponse.ok(fileTicketService.create(currentUserId, id));
    }

    @GetMapping("/{id}/images/{fileName}")
    public ResponseEntity<InputStreamResource> image(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @PathVariable("fileName") String fileName
    ) throws IOException {
        MaterialFileResource image = materialService.image(currentUserId, id, fileName);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType()))
            .contentLength(image.contentLength())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(image.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
            .body(new InputStreamResource(java.nio.file.Files.newInputStream(image.path())));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaterialDetailResponse> update(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody UpdateMaterialRequest request
    ) {
        return ApiResponse.ok(materialService.update(currentUserId, id, request));
    }

    @PostMapping("/{id}/reparse")
    public ApiResponse<MaterialDetailResponse> reparse(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.reparse(currentUserId, id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        materialService.delete(currentUserId, id);
        return ApiResponse.ok(null);
    }

    private long requireTicketOwner(String ticket, long materialId) {
        Long ownerId = fileTicketService.consume(ticket, materialId);
        if (ownerId == null) {
            throw new com.mytext.learningassistant.common.BusinessException(401, "Invalid or expired file ticket");
        }
        return ownerId;
    }
}
