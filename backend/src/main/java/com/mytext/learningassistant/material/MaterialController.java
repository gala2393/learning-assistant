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

/**
 * 学习资料 REST 控制器。
 *
 * 处理学习资料的完整生命周期，包括：
 * <ul>
 *   <li>文件上传（单次上传和分片上传）</li>
 *   <li>网页 URL 导入</li>
 *   <li>资料列表和详情查询</li>
 *   <li>知识片段和页面信息查询</li>
 *   <li>文件下载和图片访问</li>
 *   <li>资料更新、重新解析和删除</li>
 * </ul>
 *
 * 所有接口均需要通过 {@code currentUserId} 请求属性进行用户鉴权（由 AuthInterceptor 注入）。
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService materialService;
    private final MaterialFileTicketService fileTicketService;

    public MaterialController(MaterialService materialService, MaterialFileTicketService fileTicketService) {
        this.materialService = materialService;
        this.fileTicketService = fileTicketService;
    }

    /**
     * 上传学习资料文件（单次上传，适用于中小文件）。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param file          上传的文件
     * @param title         资料标题（可选，默认使用文件名）
     * @param sourceType    来源类型（可选，自动根据扩展名推断）
     * @param sourceUrl     来源 URL（可选）
     * @return 上传成功后的资料信息
     */
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

    /**
     * 通过网页 URL 导入学习资料。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param request       包含标题和来源 URL 的请求体
     * @return 导入成功后的资料信息
     */
    @PostMapping("/web")
    public ApiResponse<MaterialResponse> importWeb(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody WebMaterialRequest request
    ) {
        return ApiResponse.ok(materialService.importWeb(currentUserId, request.title(), request.sourceUrl()));
    }

    /**
     * 创建分片上传会话。
     * 大文件需要先创建会话获取 sessionId，然后逐个上传分片。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param request       包含文件元数据的创建请求
     * @return 上传会话信息（含 sessionId）
     */
    @PostMapping("/upload-sessions")
    public ApiResponse<MaterialUploadSessionResponse> createUploadSession(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody MaterialUploadSessionCreateRequest request
    ) {
        return ApiResponse.ok(materialService.createUploadSession(currentUserId, request));
    }

    /**
     * 查询分片上传会话的当前状态和进度。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param sessionId     上传会话 ID
     * @return 上传会话状态信息
     */
    @GetMapping("/upload-sessions/{sessionId}")
    public ApiResponse<MaterialUploadSessionResponse> getUploadSession(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("sessionId") String sessionId
    ) {
        return ApiResponse.ok(materialService.getUploadSession(currentUserId, sessionId));
    }

    /**
     * 上传一个文件分片。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param sessionId     上传会话 ID
     * @param chunkIndex    当前分片的索引（从 0 开始）
     * @param totalChunks   总分片数
     * @param chunk         分片文件内容
     * @param checksumSha256 分片的 SHA-256 校验值（可选）
     * @return 上传会话的最新状态
     */
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

    /**
     * 获取当前用户的学习资料列表，按创建时间降序排列。
     *
     * @param currentUserId 当前登录用户的 ID
     * @return 资料列表
     */
    @GetMapping
    public ApiResponse<List<MaterialResponse>> list(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(materialService.list(currentUserId));
    }

    /**
     * 获取单个学习资料的详细信息。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @return 资料详情
     */
    @GetMapping("/{id}")
    public ApiResponse<MaterialDetailResponse> detail(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.detail(currentUserId, id));
    }

    /**
     * 获取指定资料的所有知识片段列表。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @return 知识片段列表
     */
    @GetMapping("/{id}/chunks")
    public ApiResponse<List<MaterialChunkResponse>> chunks(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.chunks(currentUserId, id));
    }

    /**
     * 获取指定资料的页面信息列表（仅支持有预览的资料类型，如 PDF）。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @return 页面信息列表
     */
    @GetMapping("/{id}/pages")
    public ApiResponse<List<MaterialPageResponse>> pages(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.pages(currentUserId, id));
    }

    /**
     * 下载或在线预览资料文件。
     * 支持两种鉴权方式：
     * 1. 通过 Cookie（currentUserId 请求属性）
     * 2. 通过一次性 ticket 参数（用于 iframe / img 等无 Cookie 场景）
     *
     * @param currentUserId 当前登录用户的 ID（可选）
     * @param id            资料 ID
     * @param ticket        一次性下载凭据（可选）
     * @return 文件响应（包含正确的 Content-Type 和 Content-Disposition）
     * @throws IOException 读取文件失败时抛出
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<UrlResource> file(
        @RequestAttribute(value = "currentUserId", required = false) Long currentUserId,
        @PathVariable("id") long id,
        @RequestParam(value = "ticket", required = false) String ticket
    ) throws IOException {
        // 优先使用登录用户 ID，否则通过 ticket 获取 owner ID
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

    /**
     * 创建文件下载的一次性凭据（ticket）。
     * 用于前端在 iframe / img 等无法携带 Cookie 的场景中下载文件。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @return 包含 ticket 和下载 URL 的响应
     */
    @PostMapping("/{id}/file-ticket")
    public ApiResponse<MaterialFileTicketResponse> fileTicket(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        // 先验证用户有权限访问该资料
        materialService.detail(currentUserId, id);
        return ApiResponse.ok(fileTicketService.create(currentUserId, id));
    }

    /**
     * 获取资料中的图片资源（如 PDF 页面渲染图、PPT 中的图片等）。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @param fileName      图片文件名
     * @return 图片响应
     * @throws IOException 读取图片失败时抛出
     */
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

    /**
     * 更新学习资料的标题或来源 URL。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @param request       更新请求（仅更新非空字段）
     * @return 更新后的资料详情
     */
    @PutMapping("/{id}")
    public ApiResponse<MaterialDetailResponse> update(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody UpdateMaterialRequest request
    ) {
        return ApiResponse.ok(materialService.update(currentUserId, id, request));
    }

    /**
     * 重新解析学习资料。
     * 删除旧的知识片段和向量索引，重新提取文本、生成分块和 Embedding。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @return 重新解析后的资料详情
     */
    @PostMapping("/{id}/reparse")
    public ApiResponse<MaterialDetailResponse> reparse(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.reparse(currentUserId, id));
    }

    /**
     * 删除学习资料及其关联的所有数据（知识片段、向量索引、摘要、文件等）。
     *
     * @param currentUserId 当前登录用户的 ID
     * @param id            资料 ID
     * @return 空成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        materialService.delete(currentUserId, id);
        return ApiResponse.ok(null);
    }

    /**
     * 通过一次性凭据验证并获取文件所有者的用户 ID。
     *
     * @param ticket      一次性凭据 token
     * @param materialId  资料 ID
     * @return 所有者用户 ID
     * @throws BusinessException 凭据无效或已过期时抛出 401 错误
     */
    private long requireTicketOwner(String ticket, long materialId) {
        Long ownerId = fileTicketService.consume(ticket, materialId);
        if (ownerId == null) {
            throw new com.mytext.learningassistant.common.BusinessException(401, "Invalid or expired file ticket");
        }
        return ownerId;
    }
}
