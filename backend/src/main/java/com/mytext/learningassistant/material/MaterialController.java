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
 * <p>处理学习资料的完整生命周期，包括：
 * <ul>
 *   <li>文件上传（单次上传和分片上传）</li>
 *   <li>网页 URL 导入</li>
 *   <li>资料列表和详情查询</li>
 *   <li>知识片段和页面信息查询</li>
 *   <li>文件下载和图片访问</li>
 *   <li>资料更新、重新解析和删除</li>
 * </ul>
 *
 * <p>所有接口均需要通过 {@code currentUserId} 请求属性进行用户鉴权（由 AuthInterceptor 注入）。
 *
 * @see MaterialService 学习资料核心业务逻辑
 * @see MaterialFileTicketService 文件下载一次性凭据服务
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    /** 学习资料核心业务服务 */
    private final MaterialService materialService;

    /** 文件下载一次性凭据（ticket）服务，用于无 Cookie 场景下的文件鉴权 */
    private final MaterialFileTicketService fileTicketService;

    /**
     * 构造函数，通过 Spring 依赖注入初始化控制器。
     *
     * @param materialService  学习资料核心业务服务
     * @param fileTicketService 文件下载凭据服务
     */
    public MaterialController(
        MaterialService materialService,
        MaterialFileTicketService fileTicketService
    ) {
        this.materialService = materialService;
        this.fileTicketService = fileTicketService;
    }

    /**
     * 上传学习资料文件（单次上传，适用于中小文件）。
     *
     * <p>前端以 multipart/form-data 方式提交文件，后端保存文件后自动解析文本、
     * 生成知识片段（Chunk）和 Embedding 向量索引，完成后即可用于智能问答。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param file          上传的文件（必填）
     * @param title         资料标题（可选，默认使用文件名）
     * @param sourceType    来源类型（可选，为空时根据文件扩展名自动推断，如 PDF、DOCX、TXT 等）
     * @param sourceUrl     来源 URL（可选，记录资料原始来源地址）
     * @return 包装成功响应，data 中为上传成功后的资料信息
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
     * 解析临时资料文件。
     * <p>
     * 临时资料只返回提取后的文本，供智能问答本次会话使用，不会创建资料记录，也不会进入资料管理列表。
     * 适用于用户在智能问答场景中临时上传文件提问的场景。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param file          上传的文件（必填）
     * @param title         资料标题（可选，默认使用文件名）
     * @param sourceType    来源类型（可选，为空时根据扩展名自动推断）
     * @return 包含提取文本、标题、摘要等信息的临时资料响应
     */
    @PostMapping("/temporary")
    public ApiResponse<TemporaryMaterialResponse> temporary(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "sourceType", required = false) String sourceType
    ) {
        return ApiResponse.ok(materialService.parseTemporary(currentUserId, title, sourceType, file));
    }

    /**
     * 通过网页 URL 导入学习资料。
     *
     * <p>后端会发送 HTTP 请求获取网页内容，清理 HTML 标签后提取纯文本，
     * 保存为学习资料并建立索引。支持 HTTP/HTTPS 协议。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param request       包含标题（可选）和来源 URL（必填）的请求体
     * @return 包装成功响应，data 中为导入成功后的资料信息
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
     *
     * <p>持久资料使用分片上传方式：前端先调用此接口创建会话获取 sessionId，
     * 然后通过 {@code uploadChunk} 接口逐个上传分片，所有分片上传完成后系统自动合并并解析。
     *
     * <p>同一个 clientUploadId 的重复请求具有幂等性（返回已有会话）。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param request       包含文件元数据的创建请求（文件名、大小、分片大小、校验和等）
     * @return 上传会话信息（含 sessionId、总分片数、当前上传进度等）
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
     * <p>前端可通过轮询此接口获取上传和解析进度，展示给用户。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param sessionId     上传会话 ID（创建会话时返回）
     * @return 上传会话状态信息（包括上传进度、解析进度、解析阶段等）
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
     * <p>将大文件切分为多个分片逐个上传，支持以下特性：
     * <ul>
     *   <li>SHA-256 校验：可选的分片完整性校验</li>
     *   <li>幂等性：相同分片的重复上传不会出错</li>
     *   <li>自动触发：所有分片上传完毕后自动触发合并和后台解析</li>
     * </ul>
     *
     * @param currentUserId  当前登录用户的 ID（由鉴权拦截器注入）
     * @param sessionId      上传会话 ID
     * @param chunkIndex     当前分片的索引（从 0 开始）
     * @param totalChunks    总分片数（必须与创建会话时一致）
     * @param chunk          分片文件内容
     * @param checksumSha256 分片的 SHA-256 校验值（可选，提供时会进行完整性校验）
     * @return 上传会话的最新状态（包括已上传分片数、上传进度等）
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
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @return 包装成功响应，data 中为当前用户的全部资料列表
     */
    @GetMapping
    public ApiResponse<List<MaterialResponse>> list(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(materialService.list(currentUserId));
    }

    /**
     * 获取单个学习资料的详细信息。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @return 包装成功响应，data 中为资料详情（含解析状态、进度、分块数等）
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
     * <p>知识片段是将资料文本按语义拆分后的小块，每个片段都包含原文、页码、
     * 章节标题、摘要、关键词和 Embedding 向量等信息。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @return 包装成功响应，data 中为知识片段列表（按片段索引升序排列）
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
     * <p>每个页面信息包含页码、页面尺寸、页面图片文件名、关联的知识片段 ID 列表
     * 以及页面渲染状态。对于非 PDF 类型的资料，返回空列表。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @return 包装成功响应，data 中为页面信息列表
     */
    @GetMapping("/{id}/pages")
    public ApiResponse<List<MaterialPageResponse>> pages(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.pages(currentUserId, id));
    }

    /**
     * 获取指定页面的可选中文本层。
     *
     * <p>阅读器会把这些文本块覆盖到页面视觉层上，用于扫描 PDF、Office 文档等资料的原位划词。
     * 接口按页懒加载，避免大文件一次返回全部页面文本层导致前端卡顿。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            资料 ID
     * @param pageNo        页码，从 1 开始
     * @return 当前页面的文本层块列表
     */
    @GetMapping("/{id}/pages/{pageNo}/text-layer")
    public ApiResponse<List<MaterialPageTextBlockResponse>> pageTextLayer(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @PathVariable("pageNo") int pageNo
    ) {
        return ApiResponse.ok(materialService.pageTextLayer(currentUserId, id, pageNo));
    }

    /**
     * 下载或在线预览资料文件。
     *
     * <p>支持两种鉴权方式：
     * <ol>
     *   <li>通过 Cookie（currentUserId 请求属性，由 AuthInterceptor 注入）</li>
     *   <li>通过一次性 ticket 参数（用于 iframe / img 等无法携带 Cookie 的场景）</li>
     * </ol>
     *
     * <p>响应头中设置了 Content-Type（正确的 MIME 类型）、Content-Disposition（inline 模式，
     * 支持浏览器内预览）、Accept-Ranges（支持 Range 请求，用于 PDF 分页加载）和
     * Cache-Control（私有缓存 5 分钟）。
     *
     * @param currentUserId 当前登录用户的 ID（可选，为空时必须提供 ticket）
     * @param id            资料 ID（路径参数）
     * @param ticket        一次性下载凭据（可选，用于无 Cookie 场景）
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
            // 支持 HTTP Range 请求，用于 PDF 分页按需加载
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            // 私有缓存 5 分钟，避免频繁请求
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            // inline 模式：浏览器内预览而非下载
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
            .body(new UrlResource(file.path().toUri()));
    }

    /**
     * 获取资料的预览 PDF 文件。
     *
     * <p>对于 PDF 资料直接返回原始文件；对于 Word/DOCX 资料，返回通过 LibreOffice
     * 转换生成的预览 PDF。主要用于前端 PDF 阅读器的分页预览。
     *
     * <p>鉴权方式与 {@link #file} 相同，支持 Cookie 和 ticket 两种方式。
     *
     * @param currentUserId 当前登录用户的 ID（可选，为空时必须提供 ticket）
     * @param id            资料 ID（路径参数）
     * @param ticket        一次性下载凭据（可选，用于无 Cookie 场景）
     * @return 预览 PDF 文件响应
     * @throws IOException 读取文件失败时抛出
     */
    @GetMapping("/{id}/preview-file")
    public ResponseEntity<UrlResource> previewFile(
        @RequestAttribute(value = "currentUserId", required = false) Long currentUserId,
        @PathVariable("id") long id,
        @RequestParam(value = "ticket", required = false) String ticket
    ) throws IOException {
        // 优先使用登录用户 ID，否则通过 ticket 获取 owner ID
        long ownerId = currentUserId != null ? currentUserId : requireTicketOwner(ticket, id);
        MaterialFileResource file = materialService.previewFile(ownerId, id);
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
     *
     * <p>用于前端在 iframe / img 等无法携带 Cookie 的场景中下载文件。
     * 生成的 ticket 短时有效（30 分钟），可在有效期内重复使用。
     *
     * <p>典型使用流程：
     * <ol>
     *   <li>前端调用此接口获取 ticket 和拼好的下载 URL</li>
     *   <li>将 URL 设置到 iframe 的 src 或 img 的 src 中</li>
     *   <li>后端在文件下载请求中验证该 ticket</li>
     * </ol>
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @return 包含 ticket、下载 URL 和过期时间的响应
     */
    @PostMapping("/{id}/file-ticket")
    public ApiResponse<MaterialFileTicketResponse> fileTicket(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        // 先验证用户有权限访问该资料（调用 detail 会抛出 404 如果无权限）
        materialService.detail(currentUserId, id);
        return ApiResponse.ok(fileTicketService.create(currentUserId, id));
    }

    /**
     * 获取资料中的图片资源（如 PDF 页面渲染图、PPT 中的图片等）。
     *
     * <p>如果请求的页面图片尚未渲染，系统会按需触发 PDF 页面渲染生成图片。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @param fileName      图片文件名（如 "page-3.png"）
     * @return 图片响应（包含正确的 Content-Type 和 Content-Length）
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
     * <p>仅更新请求中非空的字段，不影响其他字段和已有的解析结果。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @param request       更新请求（title 和 sourceUrl 字段可选，仅更新非空字段）
     * @return 包装成功响应，data 中为更新后的资料详情
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
     *
     * <p>删除旧的知识片段和向量索引，重新提取文本、生成分块和 Embedding。
     * 适用于资料内容发生变化或需要调整解析策略的场景。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
     * @return 包装成功响应，data 中为重新解析后的资料详情
     */
    @PostMapping("/{id}/reparse")
    public ApiResponse<MaterialDetailResponse> reparse(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.reparseText(currentUserId, id));
    }

    @GetMapping("/{id}/jobs")
    public ApiResponse<List<MaterialProcessingJobResponse>> jobs(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.jobs(currentUserId, id));
    }

    @PostMapping("/{id}/jobs/{jobId}/retry")
    public ApiResponse<MaterialProcessingJobResponse> retryJob(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @PathVariable("jobId") long jobId
    ) {
        return ApiResponse.ok(materialService.retryJob(currentUserId, id, jobId));
    }

    @PostMapping("/{id}/rebuild-preview")
    public ApiResponse<MaterialDetailResponse> rebuildPreview(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.rebuildPreview(currentUserId, id));
    }

    @PostMapping("/{id}/rebuild-index")
    public ApiResponse<MaterialDetailResponse> rebuildIndex(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.rebuildIndex(currentUserId, id));
    }

    @PostMapping("/{id}/reparse-text")
    public ApiResponse<MaterialDetailResponse> reparseText(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        return ApiResponse.ok(materialService.reparseText(currentUserId, id));
    }

    /**
     * 删除学习资料及其关联的所有数据。
     *
     * <p>删除操作包括：知识片段（Chunk）、向量索引、资料摘要、问答来源关联、
     * 原始文件、预览文件和图片资源等。操作不可撤销。
     *
     * @param currentUserId 当前登录用户的 ID（由鉴权拦截器注入）
     * @param id            资料 ID（路径参数）
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
     * <p>验证逻辑：token 不能为空、ticket 记录必须存在、资料 ID 必须匹配、
     * ticket 必须在有效期内。
     *
     * @param ticket      一次性凭据 token
     * @param materialId  资料 ID（用于校验 ticket 与资料的匹配关系）
     * @return 所有者用户 ID
     * @throws BusinessException 凭据无效或已过期时抛出 401 错误
     */
    private long requireTicketOwner(String ticket, long materialId) {
        Long ownerId = fileTicketService.validate(ticket, materialId);
        if (ownerId == null) {
            throw new com.mytext.learningassistant.common.BusinessException(401, "Invalid or expired file ticket");
        }
        return ownerId;
    }

}
