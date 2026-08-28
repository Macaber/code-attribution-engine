// Application State
let state = {
    reports: [],
    selectedReport: null, // Full report details from /api/reports/{id}
    visualizationDetails: [], // Detailed chunk data from /api/reports/{id}/visualization
    activeChunk: null, // Selected chunk object
    activeTab: 'all', // 'all' or specific messageId
    hoveredLineIdx: null,
    currentPage: 1,
    pageSize: 5,
    totalPages: 1,
    filters: {
        userId: '',
        repoName: '',
        sysCode: ''
    },
    statsActiveDimension: 'sys-code',
    statsSortColumn: 'analyzedLines',
    statsSortOrder: 'desc',
    statsData: [],

    // Submitter Chunk Query State
    chunkQuery: {
        userId: '',
        repoName: '',
        sysCode: '',
        startDate: '',
        endDate: '',
        currentPage: 1,
        pageSize: 15,
        totalPages: 1,
        total: 0,
        chunks: [],
        activeChunkDetail: null,
        activeTab: 'all',
        hoveredLineIdx: null
    }
};

// DOM Elements
const reportList = document.getElementById('report-list');
const prevPageBtn = document.getElementById('prev-page-btn');
const nextPageBtn = document.getElementById('next-page-btn');
const pageIndicator = document.getElementById('page-indicator');
const reportSearchInput = document.getElementById('report-search-input');
const searchBtn = document.getElementById('search-btn');
const reportSummaryCard = document.getElementById('report-summary-card');
const fileTree = document.getElementById('file-tree');

// Summary elements
const summaryRatio = document.getElementById('summary-ratio');
const summaryLines = document.getElementById('summary-lines');
const summaryAiLines = document.getElementById('summary-ai-lines');
const summaryRepo = document.getElementById('summary-repo');
const summaryAuthor = document.getElementById('summary-author');
const summaryTime = document.getElementById('summary-time');

// Workspace elements
const noSelectionState = document.getElementById('no-selection-state');
const visualizationWorkspace = document.getElementById('visualization-workspace');
const currentChunkFile = document.getElementById('current-chunk-file');
const currentChunkLines = document.getElementById('current-chunk-lines');
const currentChunkUserId = document.getElementById('current-chunk-userId');
const currentChunkAttributionBadge = document.getElementById('current-chunk-attribution-badge');
const currentChunkScoreBadge = document.getElementById('current-chunk-score-badge');
const chunkCodeViewer = document.getElementById('chunk-code-viewer');
const toggleHighlightAll = document.getElementById('toggle-highlight-all');
const messageTabs = document.getElementById('message-tabs');
const messageContentPanel = document.getElementById('message-content-panel');
const hoverTooltip = document.getElementById('hover-tooltip');

// Initialize App
window.addEventListener('DOMContentLoaded', () => {
    initTheme();
    fetchReportsList();
    setupEventListeners();
});

// Theme Management
function initTheme() {
    const themeToggleBtn = document.getElementById('theme-toggle-btn');
    if (!themeToggleBtn) return;
    
    const sunIcon = themeToggleBtn.querySelector('.theme-icon-sun');
    const moonIcon = themeToggleBtn.querySelector('.theme-icon-moon');
    
    const savedTheme = localStorage.getItem('theme') || 'dark';
    
    if (savedTheme === 'light') {
        document.body.classList.remove('dark-theme');
        document.body.classList.add('light-theme');
        sunIcon.classList.add('hidden');
        moonIcon.classList.remove('hidden');
    } else {
        document.body.classList.remove('light-theme');
        document.body.classList.add('dark-theme');
        sunIcon.classList.remove('hidden');
        moonIcon.classList.add('hidden');
    }
    
    themeToggleBtn.addEventListener('click', () => {
        const isCurrentlyLight = document.body.classList.contains('light-theme');
        if (isCurrentlyLight) {
            document.body.classList.remove('light-theme');
            document.body.classList.add('dark-theme');
            sunIcon.classList.remove('hidden');
            moonIcon.classList.add('hidden');
            localStorage.setItem('theme', 'dark');
        } else {
            document.body.classList.remove('dark-theme');
            document.body.classList.add('light-theme');
            sunIcon.classList.add('hidden');
            moonIcon.classList.remove('hidden');
            localStorage.setItem('theme', 'light');
        }
        if (state.hoveredLineIdx !== null) {
            drawConnectionLines(state.hoveredLineIdx);
        }
    });
}

// Setup Event Listeners
function setupEventListeners() {
    prevPageBtn.addEventListener('click', () => {
        if (state.currentPage > 1) {
            state.currentPage--;
            fetchReportsList();
        }
    });

    nextPageBtn.addEventListener('click', () => {
        if (state.currentPage < state.totalPages) {
            state.currentPage++;
            fetchReportsList();
        }
    });

    searchBtn.addEventListener('click', () => {
        const query = reportSearchInput.value.trim();
        if (query) {
            if (!/^\d+$/.test(query)) {
                showToast('请输入有效的数字 Report ID 进行搜索');
                return;
            }
            loadReport(query);
        }
    });

    reportSearchInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            const query = reportSearchInput.value.trim();
            if (query) {
                if (!/^\d+$/.test(query)) {
                    showToast('请输入有效的数字 Report ID 进行搜索');
                    return;
                }
                loadReport(query);
            }
        }
    });

    // Recalculate Button Click Listener
    const recalcBtn = document.getElementById('recalc-btn');
    if (recalcBtn) {
        recalcBtn.addEventListener('click', async () => {
            const query = reportSearchInput.value.trim();
            if (!query || !/^\d+$/.test(query)) {
                showToast('请输入有效的数字 Report ID 进行重新计算');
                return;
            }
            
            try {
                showToast('正在提交重新计算任务...');
                const response = await fetch(`api/reports/${encodeURIComponent(query)}/recalculate`, {
                    method: 'POST'
                });
                
                if (!response.ok) {
                    if (response.status === 404) {
                        throw new Error('未找到该 ID 对应的报告');
                    }
                    throw new Error('提交重新计算失败');
                }
                
                showToast('🔄 重新计算任务已提交，后台处理中，3秒后自动刷新数据...');
                
                setTimeout(() => {
                    loadReport(query);
                }, 3000);
            } catch (err) {
                console.error(err);
                showToast('提交重新计算失败: ' + err.message);
            }
        });
    }

    // Sidebar Filters
    const filterBtn = document.getElementById('filter-btn');
    const filterUserId = document.getElementById('filter-user-id');
    const filterRepoName = document.getElementById('filter-repo-name');
    const filterSysCode = document.getElementById('filter-sys-code');

    if (filterBtn) {
        filterBtn.addEventListener('click', () => {
            state.filters.userId = filterUserId.value.trim();
            state.filters.repoName = filterRepoName.value.trim();
            state.filters.sysCode = filterSysCode.value.trim();
            state.currentPage = 1;
            fetchReportsList();
        });

        const filterInputs = [filterUserId, filterRepoName, filterSysCode];
        filterInputs.forEach(input => {
            if (input) {
                input.addEventListener('keypress', (e) => {
                    if (e.key === 'Enter') {
                        filterBtn.click();
                    }
                });
            }
        });
    }

    toggleHighlightAll.addEventListener('change', () => {
        renderChunkCode();
        renderRightPanel();
    });

    // Tab switching for Workspace vs Stats Dashboard vs Chunks Query Dashboard
    const navWorkspaceBtn = document.getElementById('nav-workspace-btn');
    const navStatsBtn = document.getElementById('nav-stats-btn');
    const navChunksBtn = document.getElementById('nav-chunks-btn');
    const workspaceSection = document.querySelector('.workspace');
    const statsDashboardSection = document.querySelector('.stats-dashboard');
    const chunksDashboardSection = document.querySelector('.chunks-dashboard');
    const sidebarSection = document.querySelector('.sidebar');
    const searchBarSection = document.querySelector('.report-search-bar');

    if (navWorkspaceBtn && navStatsBtn) {
        navWorkspaceBtn.addEventListener('click', () => {
            navStatsBtn.classList.remove('active');
            if (navChunksBtn) navChunksBtn.classList.remove('active');
            navWorkspaceBtn.classList.add('active');
            statsDashboardSection.classList.add('hidden');
            if (chunksDashboardSection) chunksDashboardSection.classList.add('hidden');
            workspaceSection.classList.remove('hidden');
            if (sidebarSection) sidebarSection.classList.remove('hidden');
            if (searchBarSection) searchBarSection.classList.remove('hidden');
        });

        navStatsBtn.addEventListener('click', () => {
            navWorkspaceBtn.classList.remove('active');
            if (navChunksBtn) navChunksBtn.classList.remove('active');
            navStatsBtn.classList.add('active');
            workspaceSection.classList.add('hidden');
            if (chunksDashboardSection) chunksDashboardSection.classList.add('hidden');
            statsDashboardSection.classList.remove('hidden');
            if (sidebarSection) sidebarSection.classList.add('hidden');
            if (searchBarSection) searchBarSection.classList.add('hidden');

            // Default date range to last 1 month
            const statsStartDateInput = document.getElementById('stats-start-date');
            const statsEndDateInput = document.getElementById('stats-end-date');
            if (statsStartDateInput && !statsStartDateInput.value) {
                const oneMonthAgo = new Date();
                oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);
                statsStartDateInput.value = oneMonthAgo.toISOString().split('T')[0];
            }
            if (statsEndDateInput && !statsEndDateInput.value) {
                statsEndDateInput.value = new Date().toISOString().split('T')[0];
            }

            loadGlobalStats();
        });
    }

    if (navChunksBtn) {
        navChunksBtn.addEventListener('click', () => {
            if (navWorkspaceBtn) navWorkspaceBtn.classList.remove('active');
            if (navStatsBtn) navStatsBtn.classList.remove('active');
            navChunksBtn.classList.add('active');

            if (workspaceSection) workspaceSection.classList.add('hidden');
            if (statsDashboardSection) statsDashboardSection.classList.add('hidden');
            if (chunksDashboardSection) chunksDashboardSection.classList.remove('hidden');
            if (sidebarSection) sidebarSection.classList.add('hidden');
            if (searchBarSection) searchBarSection.classList.add('hidden');

            const chunksStartDate = document.getElementById('chunks-start-date');
            const chunksEndDate = document.getElementById('chunks-end-date');
            if (chunksStartDate && !chunksStartDate.value) {
                const oneMonthAgo = new Date();
                oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);
                chunksStartDate.value = oneMonthAgo.toISOString().split('T')[0];
            }
            if (chunksEndDate && !chunksEndDate.value) {
                chunksEndDate.value = new Date().toISOString().split('T')[0];
            }

            state.chunkQuery.startDate = chunksStartDate ? chunksStartDate.value : '';
            state.chunkQuery.endDate = chunksEndDate ? chunksEndDate.value : '';

            fetchChunksList();
        });
    }

    // Chunks Query Filter Controls
    const queryChunksBtn = document.getElementById('query-chunks-btn');
    const resetChunksBtn = document.getElementById('reset-chunks-btn');
    const chunksUserIdInput = document.getElementById('chunks-user-id');
    const chunksRepoNameInput = document.getElementById('chunks-repo-name');
    const chunksSysCodeInput = document.getElementById('chunks-sys-code');
    const chunksStartDateInput = document.getElementById('chunks-start-date');
    const chunksEndDateInput = document.getElementById('chunks-end-date');
    const prevChunkPageBtn = document.getElementById('prev-chunk-page-btn');
    const nextChunkPageBtn = document.getElementById('next-chunk-page-btn');

    if (queryChunksBtn) {
        queryChunksBtn.addEventListener('click', () => {
            state.chunkQuery.userId = chunksUserIdInput ? chunksUserIdInput.value.trim() : '';
            state.chunkQuery.repoName = chunksRepoNameInput ? chunksRepoNameInput.value.trim() : '';
            state.chunkQuery.sysCode = chunksSysCodeInput ? chunksSysCodeInput.value.trim() : '';
            state.chunkQuery.startDate = chunksStartDateInput ? chunksStartDateInput.value : '';
            state.chunkQuery.endDate = chunksEndDateInput ? chunksEndDateInput.value : '';
            state.chunkQuery.currentPage = 1;
            fetchChunksList();
        });
    }

    const chunkFilterInputs = [chunksUserIdInput, chunksRepoNameInput, chunksSysCodeInput];
    chunkFilterInputs.forEach(input => {
        if (input) {
            input.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    if (queryChunksBtn) queryChunksBtn.click();
                }
            });
        }
    });

    if (resetChunksBtn) {
        resetChunksBtn.addEventListener('click', () => {
            if (chunksUserIdInput) chunksUserIdInput.value = '';
            if (chunksRepoNameInput) chunksRepoNameInput.value = '';
            if (chunksSysCodeInput) chunksSysCodeInput.value = '';
            if (chunksStartDateInput) {
                const oneMonthAgo = new Date();
                oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);
                chunksStartDateInput.value = oneMonthAgo.toISOString().split('T')[0];
            }
            if (chunksEndDateInput) {
                chunksEndDateInput.value = new Date().toISOString().split('T')[0];
            }
            state.chunkQuery.userId = '';
            state.chunkQuery.repoName = '';
            state.chunkQuery.sysCode = '';
            state.chunkQuery.startDate = chunksStartDateInput ? chunksStartDateInput.value : '';
            state.chunkQuery.endDate = chunksEndDateInput ? chunksEndDateInput.value : '';
            state.chunkQuery.currentPage = 1;
            fetchChunksList();
        });
    }

    if (prevChunkPageBtn) {
        prevChunkPageBtn.addEventListener('click', () => {
            if (state.chunkQuery.currentPage > 1) {
                state.chunkQuery.currentPage--;
                fetchChunksList();
            }
        });
    }

    if (nextChunkPageBtn) {
        nextChunkPageBtn.addEventListener('click', () => {
            if (state.chunkQuery.currentPage < state.chunkQuery.totalPages) {
                state.chunkQuery.currentPage++;
                fetchChunksList();
            }
        });
    }

    const chunkTabToggleHighlight = document.getElementById('chunk-tab-toggle-highlight');
    if (chunkTabToggleHighlight) {
        chunkTabToggleHighlight.addEventListener('change', () => {
            renderChunkTabCode();
            renderChunkTabRightPanel();
        });
    }

    // Stats Dimension Tabs Switching
    const dimensionTabs = document.querySelectorAll('.dimension-tab');
    dimensionTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            dimensionTabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            state.statsActiveDimension = tab.getAttribute('data-dimension');
            
            // Update table title
            const titleEl = document.getElementById('stats-table-title');
            if (titleEl) {
                titleEl.textContent = tab.textContent;
            }
            
            loadGlobalStats();
        });
    });

    // Stats Date Range Filter Controls
    const queryStatsBtn = document.getElementById('query-stats-btn');
    const resetStatsBtn = document.getElementById('reset-stats-btn');
    const statsStartDate = document.getElementById('stats-start-date');
    const statsEndDate = document.getElementById('stats-end-date');

    if (queryStatsBtn) {
        queryStatsBtn.addEventListener('click', () => {
            loadGlobalStats();
        });
    }

    if (resetStatsBtn) {
        resetStatsBtn.addEventListener('click', () => {
            if (statsStartDate) {
                const oneMonthAgo = new Date();
                oneMonthAgo.setMonth(oneMonthAgo.getMonth() - 1);
                statsStartDate.value = oneMonthAgo.toISOString().split('T')[0];
            }
            if (statsEndDate) {
                statsEndDate.value = new Date().toISOString().split('T')[0];
            }
            loadGlobalStats();
        });
    }
}

// Fetch list of recent reports for selector
async function fetchReportsList() {
    try {
        let relativeUrl = `api/reports?page=${state.currentPage}&pageSize=${state.pageSize}`;
        if (state.filters.userId) {
            relativeUrl += `&userId=${encodeURIComponent(state.filters.userId)}`;
        }
        if (state.filters.repoName) {
            relativeUrl += `&repoName=${encodeURIComponent(state.filters.repoName)}`;
        }
        if (state.filters.sysCode) {
            relativeUrl += `&sysCode=${encodeURIComponent(state.filters.sysCode)}`;
        }
        
        const response = await fetch(relativeUrl);
        if (!response.ok) throw new Error('无法获取报告列表');
        const result = await response.json();
        
        state.reports = result.data || [];
        const pagination = result.pagination || {};
        state.totalPages = pagination.totalPages || 1;
        state.currentPage = pagination.page || 1;

        // Render report list
        renderReportList();
        
        // Update pagination controls
        pageIndicator.textContent = `${state.currentPage} / ${state.totalPages}`;
        prevPageBtn.disabled = state.currentPage <= 1;
        nextPageBtn.disabled = state.currentPage >= state.totalPages;
    } catch (err) {
        console.error('Error fetching reports:', err);
        showToast('获取报告列表失败: ' + err.message);
    }
}

// Render report items inside report-list container
function renderReportList() {
    reportList.innerHTML = '';
    if (state.reports.length === 0) {
        reportList.innerHTML = '<div class="empty-state">暂无报告数据</div>';
        return;
    }

    state.reports.forEach(report => {
        const item = document.createElement('div');
        item.className = 'report-list-item';
        if (state.selectedReport && state.selectedReport.report && state.selectedReport.report.id === report.id) {
            item.classList.add('active');
        }

        const dateStr = new Date(report.createdAt).toLocaleDateString('zh-CN');
        const ratioPct = (report.aiContributionRatio * 100).toFixed(0) + '%';
        
        let ratioColorClass = 'color-none';
        if (report.aiContributionRatio >= 0.75) ratioColorClass = 'color-strict';
        else if (report.aiContributionRatio >= 0.25) ratioColorClass = 'color-fuzzy';

        item.innerHTML = `
            <div class="report-list-item-header">
                <span class="report-list-item-id" title="Report ID: ${report.id} / Merge ID: ${report.mergeId}">Report ID: ${report.id}</span>
                <span class="badge ${ratioColorClass}" style="font-size: 10px">${ratioPct} AI</span>
            </div>
            <div class="report-list-item-meta">
                <span class="report-list-item-repo" title="${report.repoName}">${report.repoName}</span>
                <span class="report-list-item-date">${dateStr}</span>
            </div>
        `;

        item.addEventListener('click', () => {
            document.querySelectorAll('.report-list-item').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            loadReport(report.id);
        });

        reportList.appendChild(item);
    });
}

// Reset views when no report is selected
function resetReportView() {
    state.selectedReport = null;
    state.visualizationDetails = [];
    state.activeChunk = null;
    state.activeTab = 'all';

    reportSummaryCard.classList.add('hidden');
    fileTree.innerHTML = '<div class="empty-state">请先选择或搜索一个归因报告</div>';
    noSelectionState.classList.remove('hidden');
    visualizationWorkspace.classList.add('hidden');
    renderReportList();
}

// Load report metadata and detailed visualization payload
async function loadReport(idOrMergeId) {
    showLoadingTree();
    try {
        // 1. Fetch metadata report info
        const metaResponse = await fetch(`api/reports/${encodeURIComponent(idOrMergeId)}`);
        if (!metaResponse.ok) {
            if (metaResponse.status === 404) {
                throw new Error('未找到该 ID 对应的归因报告');
            }
            throw new Error('获取报告摘要失败');
        }
        const metaData = await metaResponse.json();
        state.selectedReport = metaData;

        // Render report list again to sync the active report highlighting
        renderReportList();

        // Update Report Search Input
        reportSearchInput.value = state.selectedReport.report.id;

        // 2. Fetch visualization tracing payload
        const vizResponse = await fetch(`api/reports/${encodeURIComponent(idOrMergeId)}/visualization`);
        if (!vizResponse.ok) throw new Error('无法加载行级覆盖计算数据');
        state.visualizationDetails = await vizResponse.json();

        // 3. Render Summary Card
        renderSummaryCard();

        // 4. Render File & Chunk Navigation Tree
        renderFileTree();

        // Reset workspace selection
        noSelectionState.classList.remove('hidden');
        visualizationWorkspace.classList.add('hidden');
        state.activeChunk = null;

    } catch (err) {
        console.error(err);
        resetReportView();
        fileTree.innerHTML = `<div class="empty-state" style="color: var(--state-none)">⚠️ ${err.message}</div>`;
        showToast(err.message);
    }
}


// Render overall stats
function renderSummaryCard() {
    const report = state.selectedReport.report;
    summaryRatio.textContent = (report.aiContributionRatio * 100).toFixed(1) + '%';
    summaryLines.textContent = report.analyzedLines;
    summaryAiLines.textContent = report.aiContributedLines;
    summaryRepo.textContent = report.repoName;
    summaryAuthor.textContent = report.userId;
    summaryTime.textContent = new Date(report.createdAt).toLocaleString('zh-CN');
    
    // Group and calculate stats by chunk developer/author
    const chunkDetails = state.selectedReport.chunkDetails || [];
    const authorStats = {}; // { userId: { analyzed: 0, contributed: 0 } }
    
    chunkDetails.forEach(chunk => {
        const author = chunk.userId || '未知作者';
        if (!authorStats[author]) {
            authorStats[author] = { analyzed: 0, contributed: 0 };
        }
        authorStats[author].analyzed += chunk.analyzedLines || 0;
        authorStats[author].contributed += chunk.contributedLines || 0.0;
    });
    
    const breakdownContainer = document.getElementById('summary-author-breakdown');
    if (breakdownContainer) {
        breakdownContainer.innerHTML = '';
        
        const authors = Object.keys(authorStats);
        if (authors.length > 0) {
            const title = document.createElement('h4');
            title.textContent = '按代码提交人统计';
            breakdownContainer.appendChild(title);
            
            authors.forEach(author => {
                const stats = authorStats[author];
                const ratioVal = stats.analyzed > 0 ? (stats.contributed / stats.analyzed) : 0.0;
                
                let ratioColorClass = 'color-none';
                if (ratioVal >= 0.75) ratioColorClass = 'color-strict';
                else if (ratioVal >= 0.25) ratioColorClass = 'color-fuzzy';
                
                const item = document.createElement('div');
                item.className = 'author-breakdown-item';
                item.innerHTML = `
                    <span class="author-breakdown-name" title="${author}">${author}</span>
                    <span class="author-breakdown-ratio ${ratioColorClass}">${(ratioVal * 100).toFixed(1)}% AI (${stats.contributed.toFixed(0)}/${stats.analyzed} 行)</span>
                `;
                breakdownContainer.appendChild(item);
            });
        }
    }
    
    reportSummaryCard.classList.remove('hidden');
}

// Show a loading text in tree container
function showLoadingTree() {
    fileTree.innerHTML = `
        <div class="empty-state">
            <div class="loading-spinner"></div>
            <div style="margin-top: 10px;">正在加载并计算代码块比对数据...</div>
        </div>
    `;
}

// Render File & Chunk tree structure in the sidebar
function renderFileTree() {
    if (!state.visualizationDetails || state.visualizationDetails.length === 0) {
        fileTree.innerHTML = '<div class="empty-state">该报告无任何待分析的代码变更</div>';
        return;
    }

    // Group chunks by file path
    const fileGroups = {};
    state.visualizationDetails.forEach(chunk => {
        if (!fileGroups[chunk.filePath]) {
            fileGroups[chunk.filePath] = [];
        }
        fileGroups[chunk.filePath].push(chunk);
    });

    fileTree.innerHTML = '';
    
    // Build tree DOM
    Object.keys(fileGroups).forEach(filePath => {
        const fileNode = document.createElement('div');
        fileNode.className = 'file-node';

        // Header for file node
        const header = document.createElement('div');
        header.className = 'file-node-header';
        
        // Simple file icon and path
        const fileIcon = document.createElement('span');
        fileIcon.className = 'file-icon';
        fileIcon.textContent = '📄';
        
        const fileNameSpan = document.createElement('span');
        const parts = filePath.split('/');
        fileNameSpan.textContent = parts[parts.length - 1];
        fileNameSpan.title = filePath; // Hover tooltip shows full path

        header.appendChild(fileIcon);
        header.appendChild(fileNameSpan);
        fileNode.appendChild(header);

        // List of chunks
        const chunkList = document.createElement('div');
        chunkList.className = 'chunk-list';

        fileGroups[filePath].forEach((chunk, index) => {
            const item = document.createElement('div');
            item.className = 'chunk-item';
            
            // Format labels
            const linesLabel = `Lines ${chunk.startLine}-${chunk.endLine}`;
            const labelSpan = document.createElement('span');
            labelSpan.textContent = linesLabel;
            
            // Add status badge
            const badge = document.createElement('span');
            badge.className = `chunk-badge ${chunk.attribution}`;
            badge.textContent = chunk.attribution.replace('_', ' ');

            item.appendChild(labelSpan);
            item.appendChild(badge);

            item.addEventListener('click', () => {
                // Remove active class from all other items
                document.querySelectorAll('.chunk-item').forEach(el => el.classList.remove('active'));
                item.classList.add('active');
                selectChunk(chunk);
            });

            chunkList.appendChild(item);
        });

        fileNode.appendChild(chunkList);
        fileTree.appendChild(fileNode);
    });
}

// Select and display a Chunk in visualization workspace
function selectChunk(chunk) {
    state.activeChunk = chunk;
    state.activeTab = 'all'; // Default to show all messages combined

    // Hide empty screen, show work panel
    noSelectionState.classList.add('hidden');
    visualizationWorkspace.classList.remove('hidden');

    // Update Chunk Header details
    currentChunkFile.textContent = chunk.filePath;
    currentChunkLines.textContent = `Lines ${chunk.startLine} - ${chunk.endLine}`;
    currentChunkUserId.textContent = chunk.userId || '未知作者';
    
    // Status Badge classes
    currentChunkAttributionBadge.className = `badge-status ${chunk.attribution}`;
    currentChunkAttributionBadge.textContent = chunk.attribution.replace('_', ' ');
    currentChunkScoreBadge.textContent = (chunk.score * 100).toFixed(1) + '%';
    
    // Render code panels
    renderChunkCode();
    renderTabs();
    renderRightPanel();
}

// Render left panel (the chunk content line by line)
function renderChunkCode() {
    chunkCodeViewer.innerHTML = '';
    const chunk = state.activeChunk;
    if (!chunk) return;

    const lines = chunk.chunkContent.split('\n');
    console.log("Active Chunk filePath:", chunk.filePath);
    console.log("Chunk contributedLineIndices:", chunk.contributedLineIndices);
    const contributedSet = new Set(chunk.contributedLineIndices || []);
    
    // Find active tab contribution indices
    let activeHighlightSet = new Set();
    if (state.activeTab === 'all') {
        activeHighlightSet = contributedSet;
    } else {
        const msg = chunk.matchedMessages.find(m => m.messageId === state.activeTab);
        if (msg) {
            activeHighlightSet = new Set(msg.contributedLineIndices || []);
        }
    }
    console.log("activeHighlightSet size:", activeHighlightSet.size, Array.from(activeHighlightSet));

    const highlightEnabled = toggleHighlightAll.checked;

    lines.forEach((lineText, idx) => {
        // Line container
        const lineEl = document.createElement('div');
        lineEl.className = 'code-line clickable-line';
        
        // Line number (1-indexed based on chunk starting position)
        const lineNumVal = chunk.startLine + idx;
        const lineNumEl = document.createElement('div');
        lineNumEl.className = 'line-number';
        lineNumEl.textContent = lineNumVal;

        // Content
        const lineContentEl = document.createElement('div');
        lineContentEl.className = 'line-content';
        lineContentEl.textContent = lineText;

        lineEl.appendChild(lineNumEl);
        lineEl.appendChild(lineContentEl);

        // Apply highlighting
        if (highlightEnabled && activeHighlightSet.has(idx)) {
            if (state.activeTab === 'all') {
                lineEl.classList.add('highlight-combined');
            } else {
                lineEl.classList.add('highlight-single');
            }

            // Register hover event to show which messages cover this specific line
            lineEl.addEventListener('mouseenter', (e) => {
                state.hoveredLineIdx = idx;
                showLineTooltip(idx, e);
                drawConnectionLines(idx);
            });
            lineEl.addEventListener('mouseleave', () => {
                state.hoveredLineIdx = null;
                hideLineTooltip();
                clearConnectionLines();
            });
        }

        chunkCodeViewer.appendChild(lineEl);
    });
}

// Render dynamic matched message tabs
function renderTabs() {
    messageTabs.innerHTML = '';
    const chunk = state.activeChunk;
    if (!chunk || !chunk.matchedMessages || chunk.matchedMessages.length === 0) return;

    // 1. "All Matches" tab
    const allTab = document.createElement('button');
    allTab.className = `tab-btn ${state.activeTab === 'all' ? 'active' : ''}`;
    allTab.textContent = `全部聚合 (${chunk.matchedMessages.length})`;
    allTab.addEventListener('click', () => {
        state.activeTab = 'all';
        updateTabsActiveState();
        renderChunkCode();
        renderRightPanel();
    });
    messageTabs.appendChild(allTab);

    // 2. Individual message tabs
    chunk.matchedMessages.forEach(msg => {
        const tab = document.createElement('button');
        tab.className = `tab-btn ${state.activeTab === msg.messageId ? 'active' : ''}`;
        tab.textContent = `Msg #${msg.messageId}`;
        tab.addEventListener('click', () => {
            state.activeTab = msg.messageId;
            updateTabsActiveState();
            renderChunkCode();
            renderRightPanel();
        });
        messageTabs.appendChild(tab);
    });
}

// Fast UI state sync for tabs active class
function updateTabsActiveState() {
    const tabs = messageTabs.querySelectorAll('.tab-btn');
    const chunk = state.activeChunk;
    if (!chunk) return;

    tabs[0].classList.toggle('active', state.activeTab === 'all');
    chunk.matchedMessages.forEach((msg, idx) => {
        tabs[idx + 1].classList.toggle('active', state.activeTab === msg.messageId);
    });
}

// Render right panel (details of matched message(s) + their raw content)
function renderRightPanel() {
    messageContentPanel.innerHTML = '';
    const chunk = state.activeChunk;
    if (!chunk) return;

    if (!chunk.matchedMessages || chunk.matchedMessages.length === 0) {
        messageContentPanel.innerHTML = `
            <div class="empty-state">
                <span style="font-size: 32px; margin-bottom: 12px;">🔍</span>
                <p>无匹配的 AI 消息源数据</p>
                <span style="font-size: 11px; color: var(--text-muted); margin-top: 4px;">归因结果为 [NONE]</span>
            </div>
        `;
        return;
    }

    if (state.activeTab === 'all') {
        // Render all matched messages sequentially
        chunk.matchedMessages.forEach(msg => {
            const card = buildMessageCard(msg);
            messageContentPanel.appendChild(card);
        });
    } else {
        // Render single selected message details
        const msg = chunk.matchedMessages.find(m => m.messageId === state.activeTab);
        if (msg) {
            const card = buildMessageCard(msg, true);
            messageContentPanel.appendChild(card);
        }
    }
}

// Helper to construct a single message card DOM element
function buildMessageCard(msg, showExpanded = false) {
    const card = document.createElement('div');
    card.className = 'message-card';
    card.style.display = 'flex';
    card.style.flexDirection = 'column';
    card.style.borderBottom = '1px solid var(--border-color)';
    card.style.paddingBottom = '16px';
    card.style.background = 'rgba(255, 255, 255, 0.01)';

    const scorePct = (msg.score * 100).toFixed(1) + '%';
    const dateStr = new Date(msg.timestamp).toLocaleString('zh-CN');

    // Message Metadata
    const metaHtml = `
        <div class="message-metadata-card">
            <div class="meta-header">
                <h4>🤖 AI Message ID: <span class="color-strict" style="font-family: var(--font-mono)">${escapeHtml(msg.messageId)}</span></h4>
                <div class="score-visual">
                    <span class="badge-score" style="color: #6ee7b7">${escapeHtml(scorePct)} Match</span>
                    <div class="score-bar-bg">
                        <div class="score-bar-fill" style="width: ${msg.score * 100}%"></div>
                    </div>
                </div>
            </div>
            <div class="meta-details">
                <div class="meta-item">匹配类型: <strong>${escapeHtml(msg.matchType)}</strong></div>
                <div class="meta-item">文件关联: <strong title="${escapeHtml(msg.fileName || '未定义')}">${escapeHtml(msg.fileName) || '无指定'}</strong></div>
                <div class="meta-item" style="grid-column: span 2">产生时间: <strong>${escapeHtml(dateStr)}</strong></div>
            </div>
        </div>
    `;

    card.innerHTML = metaHtml;

    // Code container
    const codeTitle = document.createElement('div');
    codeTitle.className = 'ai-code-viewer-title';
    codeTitle.textContent = 'AI 消息源码 (Raw Content)';
    card.appendChild(codeTitle);

    const codeContainer = document.createElement('div');
    codeContainer.className = 'code-container';
    codeContainer.style.background = 'var(--bg-code)';
    codeContainer.style.margin = '4px 20px 8px 20px';
    codeContainer.style.borderRadius = '8px';
    codeContainer.style.border = '1px solid var(--border-color)';

    const codeWrapper = document.createElement('div');
    codeWrapper.className = 'code-wrapper';
    codeWrapper.style.padding = '12px 0';
    codeWrapper.style.maxHeight = '360px';
    codeWrapper.style.overflowY = 'auto';

    const aiToChunkMap = {};
    console.log("Message ID:", msg.messageId);
    console.log("Message lineMatches raw:", msg.lineMatches);
    if (msg.lineMatches) {
        msg.lineMatches.forEach(m => {
            aiToChunkMap[m.aiLineIdx] = m.chunkLineIdx;
        });
    }
    console.log("Compiled aiToChunkMap:", aiToChunkMap);

    const aiLines = msg.rawContent.split('\n');
    aiLines.forEach((lineText, idx) => {
        const lineEl = document.createElement('div');
        lineEl.className = 'code-line ai-code-line';
        lineEl.setAttribute('data-line-idx', idx);
        lineEl.setAttribute('data-msg-id', msg.messageId);

        const lineNumEl = document.createElement('div');
        lineNumEl.className = 'line-number';
        lineNumEl.textContent = idx + 1;

        const lineContentEl = document.createElement('div');
        lineContentEl.className = 'line-content';
        lineContentEl.textContent = lineText;

        lineEl.appendChild(lineNumEl);
        lineEl.appendChild(lineContentEl);

        if (aiToChunkMap[idx] !== undefined) {
            lineEl.classList.add('clickable-line');
            
            if (toggleHighlightAll.checked) {
                lineEl.classList.add('highlight-ai-permanent');
            }
            
            lineEl.addEventListener('mouseenter', () => {
                const chunkLineIdx = aiToChunkMap[idx];
                state.hoveredLineIdx = chunkLineIdx;
                drawConnectionLines(chunkLineIdx);
                
                const chunkLineEl = chunkCodeViewer.children[chunkLineIdx];
                if (chunkLineEl) {
                    chunkLineEl.classList.add('hover-highlight-from-ai');
                }
            });
            lineEl.addEventListener('mouseleave', () => {
                const chunkLineIdx = aiToChunkMap[idx];
                state.hoveredLineIdx = null;
                clearConnectionLines();
                
                const chunkLineEl = chunkCodeViewer.children[chunkLineIdx];
                if (chunkLineEl) {
                    chunkLineEl.classList.remove('hover-highlight-from-ai');
                }
            });
        }

        codeWrapper.appendChild(lineEl);
    });

    codeContainer.appendChild(codeWrapper);
    card.appendChild(codeContainer);

    return card;
}

// Show Tooltip when hovering over highlighted lines
function showLineTooltip(lineIdx, event) {
    const chunk = state.activeChunk;
    if (!chunk || !chunk.matchedMessages) return;

    // Find all messages that matched this specific line index
    const matchingMsgs = chunk.matchedMessages.filter(msg => {
        const indices = msg.contributedLineIndices || [];
        return indices.includes(lineIdx);
    });

    if (matchingMsgs.length === 0) return;

    hoverTooltip.innerHTML = '';
    
    const title = document.createElement('div');
    title.className = 'tooltip-title';
    title.textContent = `此行属于 AI 贡献 (Row ${chunk.startLine + lineIdx})`;
    hoverTooltip.appendChild(title);

    matchingMsgs.forEach(msg => {
        const line = document.createElement('div');
        line.className = 'tooltip-line';
        line.innerHTML = `• Msg <strong class="color-strict">${msg.messageId}</strong>: 匹配度 ${(msg.score * 100).toFixed(0)}% (${msg.matchType})`;
        hoverTooltip.appendChild(line);
    });

    hoverTooltip.classList.remove('hidden');
}

function hideLineTooltip() {
    hoverTooltip.classList.add('hidden');
}

// Toast indicator helper
function showToast(message) {
    const toast = document.createElement('div');
    toast.style.position = 'fixed';
    toast.style.bottom = '24px';
    toast.style.right = '24px';
    toast.style.background = 'rgba(13, 17, 28, 0.95)';
    toast.style.border = '1px solid var(--accent-indigo)';
    toast.style.borderRadius = '8px';
    toast.style.padding = '12px 20px';
    toast.style.boxShadow = '0 10px 30px rgba(0,0,0,0.5)';
    toast.style.color = '#fff';
    toast.style.fontSize = '13px';
    toast.style.zIndex = '1000';
    toast.style.animation = 'fadeIn 0.2s ease-out';
    toast.textContent = message;

    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transition = 'opacity 0.5s ease-out';
        setTimeout(() => toast.remove(), 500);
    }, 4000);
}

// Draw Connecting Lines between Chunk and AI Message Matching Lines (Line highlights active on hover)
function drawConnectionLines(chunkLineIdx) {
    clearConnectionLines();
    
    const svg = document.getElementById('connection-svg');
    if (!svg) return;
    
    const chunk = state.activeChunk;
    if (!chunk) return;
    
    const chunkLineEl = chunkCodeViewer.children[chunkLineIdx];
    if (!chunkLineEl) return;
    
    const splitPane = document.querySelector('.split-pane');
    const paneRect = splitPane.getBoundingClientRect();
    const chunkLineRect = chunkLineEl.getBoundingClientRect();
    
    const yLeft = chunkLineRect.top - paneRect.top + chunkLineRect.height / 2;
    const xLeft = chunkLineRect.right - paneRect.left;
    
    // Get messages to check based on the active tab
    let messagesToCheck = [];
    if (state.activeTab === 'all') {
        messagesToCheck = chunk.matchedMessages || [];
    } else {
        const activeMsg = chunk.matchedMessages.find(m => m.messageId === state.activeTab);
        if (activeMsg) messagesToCheck = [activeMsg];
    }
    
    messagesToCheck.forEach(msg => {
        if (!msg.lineMatches) return;
        const matches = msg.lineMatches.filter(m => m.chunkLineIdx === chunkLineIdx);
        
        matches.forEach(match => {
            const aiLineEl = messageContentPanel.querySelector(
                `.ai-code-line[data-msg-id="${msg.messageId}"][data-line-idx="${match.aiLineIdx}"]`
            );
            if (!aiLineEl) return;
            
            aiLineEl.classList.add('highlight-ai-line');
            
            const aiLineRect = aiLineEl.getBoundingClientRect();
            const yRight = aiLineRect.top - paneRect.top + aiLineRect.height / 2;
            const xRight = aiLineRect.left - paneRect.left;
            
            // Draw smooth bezier curve
            const dx = Math.min(80, Math.abs(xRight - xLeft) / 2);
            const pathData = `M ${xLeft} ${yLeft} C ${xLeft + dx} ${yLeft}, ${xRight - dx} ${yRight}, ${xRight} ${yRight}`;
            
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', pathData);
            path.setAttribute('class', 'connection-path');
            svg.appendChild(path);
        });
    });
}

function clearConnectionLines() {
    const svg = document.getElementById('connection-svg');
    if (svg) svg.innerHTML = '';
    
    document.querySelectorAll('.ai-code-line.highlight-ai-line').forEach(el => {
        el.classList.remove('highlight-ai-line');
    });
}

// Redraw connecting lines on scroll or resize events
document.addEventListener('scroll', () => {
    if (state.hoveredLineIdx !== null) {
        drawConnectionLines(state.hoveredLineIdx);
    }
}, true);

window.addEventListener('resize', () => {
    if (state.hoveredLineIdx !== null) {
        drawConnectionLines(state.hoveredLineIdx);
    }
});

// Fetch and render the global aggregated statistics
async function loadGlobalStats() {
    const table = document.getElementById('dynamic-stats-table');
    const thead = table ? table.querySelector('thead') : null;
    const tbody = table ? table.querySelector('tbody') : null;
    
    const statsTotalReports = document.getElementById('stats-total-reports');
    const statsTotalLines = document.getElementById('stats-total-lines');
    const statsTotalAiLines = document.getElementById('stats-total-ai-lines');
    const statsOverallRatio = document.getElementById('stats-overall-ratio');

    const startDateVal = document.getElementById('stats-start-date')?.value || '';
    const endDateVal = document.getElementById('stats-end-date')?.value || '';

    // Show loading indicator
    if (tbody) tbody.innerHTML = '<tr><td colspan="4" class="text-center">正在加载数据...</td></tr>';

    try {
        // 1. Fetch overall summary statistics for timeframe
        let summaryUrl = `api/reports/stats/summary?`;
        if (startDateVal) summaryUrl += `startDate=${encodeURIComponent(startDateVal)}&`;
        if (endDateVal) summaryUrl += `endDate=${encodeURIComponent(endDateVal)}&`;
        
        const summaryRes = await fetch(summaryUrl);
        if (summaryRes.ok) {
            const summaryData = await summaryRes.json();
            if (statsTotalReports) statsTotalReports.textContent = summaryData.totalReports || 0;
            if (statsTotalLines) statsTotalLines.textContent = summaryData.totalAnalyzedLines || 0;
            if (statsTotalAiLines) statsTotalAiLines.textContent = Math.round(summaryData.totalAiContributedLines) || 0;
            
            const overallRatio = summaryData.totalAnalyzedLines > 0 
                ? (summaryData.totalAiContributedLines / summaryData.totalAnalyzedLines * 100).toFixed(1) + '%' 
                : '0.0%';
            if (statsOverallRatio) statsOverallRatio.textContent = overallRatio;
        }

        // 2. Fetch active dimension breakdown data and render with sorting
        if (thead && tbody) {
            let breakdownUrl = `api/reports/stats/breakdown?groupBy=${state.statsActiveDimension}&`;
            if (startDateVal) breakdownUrl += `startDate=${encodeURIComponent(startDateVal)}&`;
            if (endDateVal) breakdownUrl += `endDate=${encodeURIComponent(endDateVal)}&`;
            
            const breakdownRes = await fetch(breakdownUrl);
            if (!breakdownRes.ok) throw new Error('无法加载出码率明细统计');
            
            state.statsData = await breakdownRes.json();
            renderStatsTable();
        }

    } catch (err) {
        console.error(err);
        showToast('加载出码率汇总统计失败: ' + err.message);
        if (tbody) tbody.innerHTML = `<tr><td colspan="4" class="text-center text-error">⚠️ ${err.message}</td></tr>`;
    }
}

// Render dynamic stats table with sorting capabilities
function renderStatsTable() {
    const table = document.getElementById('dynamic-stats-table');
    const thead = table ? table.querySelector('thead') : null;
    const tbody = table ? table.querySelector('tbody') : null;
    if (!thead || !tbody) return;

    // 1. Sort the cached statsData based on state configuration
    const col = state.statsSortColumn;
    const order = state.statsSortOrder;
    const sortedData = [...state.statsData].sort((a, b) => {
        let valA = a[col];
        let valB = b[col];

        if (col === 'name') {
            valA = (valA || '').toString();
            valB = (valB || '').toString();
            return order === 'asc' ? valA.localeCompare(valB) : valB.localeCompare(valA);
        }

        valA = Number(valA) || 0;
        valB = Number(valB) || 0;
        return order === 'asc' ? valA - valB : valB - valA;
    });

    // 2. Determine correct dynamic dimension header
    let firstColHeader = '系统代码';
    if (state.statsActiveDimension === 'repo-name') {
        firstColHeader = '仓库名称';
    } else if (state.statsActiveDimension === 'developer') {
        firstColHeader = '代码提交人';
    }

    const getIndicator = (columnName) => {
        if (state.statsSortColumn !== columnName) return ' <span style="font-size: 10px; opacity: 0.4;">⇅</span>';
        return state.statsSortOrder === 'asc' 
            ? ' <span style="color: var(--accent-indigo); font-size: 11px;">▲</span>' 
            : ' <span style="color: var(--accent-indigo); font-size: 11px;">▼</span>';
    };

    thead.innerHTML = `
        <tr>
            <th data-sort="name" style="cursor: pointer; user-select: none;">${firstColHeader}${getIndicator('name')}</th>
            <th data-sort="analyzedLines" style="cursor: pointer; user-select: none;">分析行数${getIndicator('analyzedLines')}</th>
            <th data-sort="aiContributedLines" style="cursor: pointer; user-select: none;">AI 贡献行数${getIndicator('aiContributedLines')}</th>
            <th data-sort="aiRatio" style="cursor: pointer; user-select: none;">AI 占比${getIndicator('aiRatio')}</th>
        </tr>
    `;

    // Rebind click listeners to headers
    thead.querySelectorAll('th').forEach(th => {
        th.addEventListener('click', () => {
            const targetCol = th.getAttribute('data-sort');
            if (state.statsSortColumn === targetCol) {
                state.statsSortOrder = state.statsSortOrder === 'asc' ? 'desc' : 'asc';
            } else {
                state.statsSortColumn = targetCol;
                state.statsSortOrder = 'desc';
            }
            renderStatsTable();
        });
    });

    // 3. Render Body Rows
    tbody.innerHTML = '';
    if (sortedData.length === 0) {
        tbody.innerHTML = '<tr><td colspan="4" class="text-center">该时间段内暂无数据</td></tr>';
        return;
    }

    sortedData.forEach(item => {
        const ratioVal = item.aiRatio;
        const ratioPct = (ratioVal * 100).toFixed(1) + '%';
        const fillWidth = (ratioVal * 100).toFixed(0) + '%';
        
        let ratioColorClass = 'color-none';
        if (ratioVal >= 0.75) ratioColorClass = 'color-strict';
        else if (ratioVal >= 0.25) ratioColorClass = 'color-fuzzy';

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td style="font-weight: 600;">${item.name}</td>
            <td>${item.analyzedLines} 行</td>
            <td>${item.aiContributedLines.toFixed(0)} 行</td>
            <td>
                <div style="display: flex; align-items: center; gap: 10px;">
                    <span class="${ratioColorClass}" style="font-weight: 600; width: 50px;">${ratioPct}</span>
                    <div style="flex: 1; height: 6px; background: var(--bg-tab); border-radius: 3px; overflow: hidden; min-width: 80px; max-width: 200px;">
                        <div style="height: 100%; width: ${fillWidth}; background: var(--accent-indigo); border-radius: 3px;"></div>
                    </div>
                </div>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

// ── Submitter Chunks Query Dashboard Logic ──

// Escape untrusted text (file paths, repo names, user ids...) before inserting into innerHTML templates
function escapeHtml(value) {
    if (value === null || value === undefined) return '';
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

async function fetchChunksList() {
    const listContainer = document.getElementById('chunks-list-container');
    const totalCountSpan = document.getElementById('chunks-total-count');
    const pageIndicator = document.getElementById('chunk-page-indicator');
    const prevBtn = document.getElementById('prev-chunk-page-btn');
    const nextBtn = document.getElementById('next-chunk-page-btn');

    if (!listContainer) return;
    listContainer.innerHTML = '<div class="empty-state"><div class="loading-spinner"></div><div style="margin-top:10px;">正在查询 Chunk 数据...</div></div>';

    try {
        const params = new URLSearchParams({
            page: state.chunkQuery.currentPage,
            pageSize: state.chunkQuery.pageSize
        });
        if (state.chunkQuery.userId) params.append('userId', state.chunkQuery.userId);
        if (state.chunkQuery.repoName) params.append('repoName', state.chunkQuery.repoName);
        if (state.chunkQuery.sysCode) params.append('sysCode', state.chunkQuery.sysCode);
        if (state.chunkQuery.startDate) params.append('startDate', state.chunkQuery.startDate);
        if (state.chunkQuery.endDate) params.append('endDate', state.chunkQuery.endDate);

        const res = await fetch(`api/reports/chunks?${params.toString()}`);
        if (!res.ok) throw new Error('查询 Chunk 列表失败');
        const data = await res.json();

        state.chunkQuery.chunks = data.data || [];
        state.chunkQuery.totalPages = data.pagination ? data.pagination.totalPages : 1;
        state.chunkQuery.total = data.pagination ? data.pagination.total : 0;

        // Render summary metrics under current filter conditions
        const summary = data.summary || {};
        const summaryAnalyzed = document.getElementById('chunks-summary-analyzed');
        const summaryAiLines = document.getElementById('chunks-summary-ai-lines');
        const summaryRatio = document.getElementById('chunks-summary-ratio');

        if (summaryAnalyzed) summaryAnalyzed.textContent = (summary.totalAnalyzedLines || 0).toLocaleString();
        if (summaryAiLines) summaryAiLines.textContent = Math.round(summary.totalAiContributedLines || 0).toLocaleString();
        if (summaryRatio) {
            const ratioPct = ((summary.aiRatio || 0) * 100).toFixed(1) + '%';
            summaryRatio.textContent = ratioPct;
        }

        if (totalCountSpan) totalCountSpan.textContent = `共 ${state.chunkQuery.total} 条`;
        if (pageIndicator) pageIndicator.textContent = `${state.chunkQuery.currentPage} / ${state.chunkQuery.totalPages || 1}`;
        if (prevBtn) prevBtn.disabled = state.chunkQuery.currentPage <= 1;
        if (nextBtn) nextBtn.disabled = state.chunkQuery.currentPage >= state.chunkQuery.totalPages;

        renderChunksList();
    } catch (err) {
        console.error(err);
        listContainer.innerHTML = `<div class="empty-state">查询 Chunk 失败: ${err.message}</div>`;
    }
}

function renderChunksList() {
    const listContainer = document.getElementById('chunks-list-container');
    if (!listContainer) return;
    listContainer.innerHTML = '';

    if (!state.chunkQuery.chunks || state.chunkQuery.chunks.length === 0) {
        listContainer.innerHTML = '<div class="empty-state">未找到匹配的 Chunk 记录</div>';
        return;
    }

    state.chunkQuery.chunks.forEach(chunk => {
        const item = document.createElement('div');
        item.className = 'chunk-result-card';
        if (state.chunkQuery.activeChunkDetail && state.chunkQuery.activeChunkDetail.chunkId === chunk.id) {
            item.classList.add('active');
        }

        const scorePercent = chunk.score != null ? (chunk.score * 100).toFixed(1) + '%' : '0.0%';
        const attributionClass = chunk.attribution || 'none';
        const formattedDate = chunk.reportCreatedAt ? chunk.reportCreatedAt.replace('T', ' ').substring(0, 16) : '-';

        item.innerHTML = `
            <div class="card-title-row">
                <span class="file-path-text" title="${escapeHtml(chunk.filePath)}">${escapeHtml(chunk.filePath)}</span>
                <span class="chunk-badge ${escapeHtml(attributionClass)}">${escapeHtml(attributionClass.replace('_', ' '))} ${escapeHtml(scorePercent)}</span>
            </div>
            <div class="card-info-row">
                <span class="badge badge-lines">Lines ${escapeHtml(chunk.startLine)}-${escapeHtml(chunk.endLine)} (${escapeHtml(chunk.analyzedLines || 0)}行)</span>
                <span class="badge badge-user">👤 ${escapeHtml(chunk.userId) || '未知提交人'}</span>
            </div>
            <div class="card-meta-row">
                <span class="meta-tag tag-repo" title="仓库: ${escapeHtml(chunk.repoName)}">📦 ${escapeHtml(chunk.repoName) || '-'}</span>
                <span class="meta-tag tag-sys" title="系统: ${escapeHtml(chunk.sysCode)}">🖥️ ${escapeHtml(chunk.sysCode) || '-'}</span>
            </div>
            <div class="card-branch-row">
                <span class="meta-tag tag-branch" title="分支: ${escapeHtml(chunk.source)} -> ${escapeHtml(chunk.target)}">🔀 ${escapeHtml(chunk.source) || '-'} ➔ ${escapeHtml(chunk.target) || '-'}</span>
                <span class="meta-time">${escapeHtml(formattedDate)}</span>
            </div>
        `;

        item.addEventListener('click', () => {
            document.querySelectorAll('.chunk-result-card').forEach(el => el.classList.remove('active'));
            item.classList.add('active');
            loadChunkDetail(chunk.id);
        });

        listContainer.appendChild(item);
    });
}

async function loadChunkDetail(chunkId) {
    const noState = document.getElementById('chunks-no-selection-state');
    const vizWorkspace = document.getElementById('chunks-visualization-workspace');
    if (!vizWorkspace) return;

    try {
        const res = await fetch(`api/reports/chunks/${chunkId}/detail`);
        if (!res.ok) throw new Error('获取 Chunk 详情失败');
        const detail = await res.json();

        state.chunkQuery.activeChunkDetail = detail;
        state.chunkQuery.activeTab = 'all';

        if (noState) noState.classList.add('hidden');
        vizWorkspace.classList.remove('hidden');

        // Header metadata
        const filePathEl = document.getElementById('chunk-tab-file-path');
        if (filePathEl) filePathEl.textContent = detail.filePath;

        const linesBadge = document.getElementById('chunk-tab-lines-badge');
        if (linesBadge) linesBadge.textContent = `Lines ${detail.startLine} - ${detail.endLine}`;

        const userBadge = document.getElementById('chunk-tab-user-badge');
        if (userBadge) userBadge.textContent = `👤 ${detail.userId || '未知提交人'}`;

        const repoBadge = document.getElementById('chunk-tab-repo-badge');
        if (repoBadge) repoBadge.textContent = `📦 ${detail.repoName || '-'}`;

        const sysBadge = document.getElementById('chunk-tab-sys-badge');
        if (sysBadge) sysBadge.textContent = `🖥️ ${detail.sysCode || '-'}`;

        const branchBadge = document.getElementById('chunk-tab-branch-badge');
        if (branchBadge) branchBadge.textContent = `🔀 ${detail.source || '-'} ➔ ${detail.target || '-'}`;

        const attrBadge = document.getElementById('chunk-tab-attribution-badge');
        if (attrBadge) {
            attrBadge.className = `badge-status ${detail.attribution}`;
            attrBadge.textContent = (detail.attribution || 'none').replace('_', ' ');
        }

        const scoreBadge = document.getElementById('chunk-tab-score-badge');
        if (scoreBadge) scoreBadge.textContent = ((detail.score || 0) * 100).toFixed(1) + '%';

        renderChunkTabCode();
        renderChunkTabTabs();
        renderChunkTabRightPanel();
    } catch (err) {
        console.error(err);
        showToast('加载 Chunk 详情失败: ' + err.message);
    }
}

function renderChunkTabCode() {
    const codeViewer = document.getElementById('chunk-tab-code-viewer');
    if (!codeViewer) return;
    codeViewer.innerHTML = '';

    const detail = state.chunkQuery.activeChunkDetail;
    if (!detail || !detail.chunkContent) return;

    const lines = detail.chunkContent.split('\n');
    const contributedSet = new Set(detail.contributedLineIndices || []);

    let activeHighlightSet = new Set();
    if (state.chunkQuery.activeTab === 'all') {
        activeHighlightSet = contributedSet;
    } else {
        const msg = (detail.matchedMessages || []).find(m => m.messageId === state.chunkQuery.activeTab);
        if (msg) {
            activeHighlightSet = new Set(msg.contributedLineIndices || []);
        }
    }

    const toggleBtn = document.getElementById('chunk-tab-toggle-highlight');
    const highlightEnabled = toggleBtn ? toggleBtn.checked : true;

    lines.forEach((lineText, idx) => {
        const lineEl = document.createElement('div');
        lineEl.className = 'code-line clickable-line';

        const lineNumVal = detail.startLine + idx;
        const lineNumEl = document.createElement('div');
        lineNumEl.className = 'line-number';
        lineNumEl.textContent = lineNumVal;

        const lineContentEl = document.createElement('div');
        lineContentEl.className = 'line-content';
        lineContentEl.textContent = lineText;

        lineEl.appendChild(lineNumEl);
        lineEl.appendChild(lineContentEl);

        if (highlightEnabled && activeHighlightSet.has(idx)) {
            if (state.chunkQuery.activeTab === 'all') {
                lineEl.classList.add('highlight-combined');
            } else {
                lineEl.classList.add('highlight-single');
            }

            lineEl.addEventListener('mouseenter', (e) => {
                state.chunkQuery.hoveredLineIdx = idx;
                showLineTooltipForChunk(idx, e);
                drawChunkTabConnectionLines(idx);
            });
            lineEl.addEventListener('mouseleave', () => {
                state.chunkQuery.hoveredLineIdx = null;
                hideLineTooltip();
                clearChunkTabConnectionLines();
            });
        }

        codeViewer.appendChild(lineEl);
    });
}

function renderChunkTabTabs() {
    const tabsPanel = document.getElementById('chunk-tab-message-tabs');
    if (!tabsPanel) return;
    tabsPanel.innerHTML = '';

    const detail = state.chunkQuery.activeChunkDetail;
    if (!detail || !detail.matchedMessages || detail.matchedMessages.length === 0) return;

    const allTab = document.createElement('button');
    allTab.className = `tab-btn ${state.chunkQuery.activeTab === 'all' ? 'active' : ''}`;
    allTab.textContent = `全部聚合 (${detail.matchedMessages.length})`;
    allTab.addEventListener('click', () => {
        state.chunkQuery.activeTab = 'all';
        updateChunkTabTabsActiveState();
        renderChunkTabCode();
        renderChunkTabRightPanel();
    });
    tabsPanel.appendChild(allTab);

    detail.matchedMessages.forEach(msg => {
        const tab = document.createElement('button');
        tab.className = `tab-btn ${state.chunkQuery.activeTab === msg.messageId ? 'active' : ''}`;
        tab.textContent = `Msg #${msg.messageId}`;
        tab.addEventListener('click', () => {
            state.chunkQuery.activeTab = msg.messageId;
            updateChunkTabTabsActiveState();
            renderChunkTabCode();
            renderChunkTabRightPanel();
        });
        tabsPanel.appendChild(tab);
    });
}

function updateChunkTabTabsActiveState() {
    const tabsPanel = document.getElementById('chunk-tab-message-tabs');
    if (!tabsPanel) return;
    const tabs = tabsPanel.querySelectorAll('.tab-btn');
    const detail = state.chunkQuery.activeChunkDetail;
    if (!detail || tabs.length === 0) return;

    tabs[0].classList.toggle('active', state.chunkQuery.activeTab === 'all');
    (detail.matchedMessages || []).forEach((msg, idx) => {
        if (tabs[idx + 1]) {
            tabs[idx + 1].classList.toggle('active', state.chunkQuery.activeTab === msg.messageId);
        }
    });
}

function renderChunkTabRightPanel() {
    const panel = document.getElementById('chunk-tab-message-content-panel');
    if (!panel) return;
    panel.innerHTML = '';

    const detail = state.chunkQuery.activeChunkDetail;
    if (!detail) return;

    if (!detail.matchedMessages || detail.matchedMessages.length === 0) {
        panel.innerHTML = `
            <div class="empty-state">
                <span style="font-size: 32px; margin-bottom: 12px;">🔍</span>
                <p>无匹配的 AI 消息源数据</p>
                <span style="font-size: 11px; color: var(--text-muted); margin-top: 4px;">归因结果为 [NONE]</span>
            </div>
        `;
        return;
    }

    if (state.chunkQuery.activeTab === 'all') {
        detail.matchedMessages.forEach(msg => {
            const card = buildChunkTabMessageCard(msg);
            panel.appendChild(card);
        });
    } else {
        const msg = detail.matchedMessages.find(m => m.messageId === state.chunkQuery.activeTab);
        if (msg) {
            const card = buildChunkTabMessageCard(msg, true);
            panel.appendChild(card);
        }
    }
}

function buildChunkTabMessageCard(msg) {
    const card = document.createElement('div');
    card.className = 'message-card';
    card.style.display = 'flex';
    card.style.flexDirection = 'column';
    card.style.borderBottom = '1px solid var(--border-color)';
    card.style.paddingBottom = '16px';
    card.style.background = 'rgba(255, 255, 255, 0.01)';

    const scorePct = (msg.score * 100).toFixed(1) + '%';
    const dateStr = msg.timestamp ? new Date(msg.timestamp).toLocaleString('zh-CN') : '-';

    const metaHtml = `
        <div class="message-metadata-card">
            <div class="meta-header">
                <h4>🤖 AI Message ID: <span class="color-strict" style="font-family: var(--font-mono)">${escapeHtml(msg.messageId)}</span></h4>
                <div class="score-visual">
                    <span class="badge-score" style="color: #6ee7b7">${escapeHtml(scorePct)} Match</span>
                    <div class="score-bar-bg">
                        <div class="score-bar-fill" style="width: ${msg.score * 100}%"></div>
                    </div>
                </div>
            </div>
            <div class="meta-details">
                <div class="meta-item">匹配类型: <strong>${escapeHtml(msg.matchType)}</strong></div>
                <div class="meta-item">文件关联: <strong title="${escapeHtml(msg.fileName || '未定义')}">${escapeHtml(msg.fileName) || '无指定'}</strong></div>
                <div class="meta-item" style="grid-column: span 2">产生时间: <strong>${escapeHtml(dateStr)}</strong></div>
            </div>
        </div>
    `;

    card.innerHTML = metaHtml;

    const codeTitle = document.createElement('div');
    codeTitle.className = 'ai-code-viewer-title';
    codeTitle.textContent = 'AI 消息源码 (Raw Content)';
    card.appendChild(codeTitle);

    const codeContainer = document.createElement('div');
    codeContainer.className = 'code-container';
    codeContainer.style.background = 'var(--bg-code)';
    codeContainer.style.margin = '4px 20px 8px 20px';
    codeContainer.style.borderRadius = '8px';
    codeContainer.style.border = '1px solid var(--border-color)';

    const codeWrapper = document.createElement('div');
    codeWrapper.className = 'code-wrapper';
    codeWrapper.style.padding = '12px 0';
    codeWrapper.style.maxHeight = '360px';
    codeWrapper.style.overflowY = 'auto';

    const aiToChunkMap = {};
    if (msg.lineMatches) {
        msg.lineMatches.forEach(m => {
            aiToChunkMap[m.aiLineIdx] = m.chunkLineIdx;
        });
    }

    const toggleBtn = document.getElementById('chunk-tab-toggle-highlight');
    const highlightEnabled = toggleBtn ? toggleBtn.checked : true;
    const codeViewer = document.getElementById('chunk-tab-code-viewer');

    const aiLines = (msg.rawContent || '').split('\n');
    aiLines.forEach((lineText, idx) => {
        const lineEl = document.createElement('div');
        lineEl.className = 'code-line ai-code-line';
        lineEl.setAttribute('data-line-idx', idx);
        lineEl.setAttribute('data-msg-id', msg.messageId);

        const lineNumEl = document.createElement('div');
        lineNumEl.className = 'line-number';
        lineNumEl.textContent = idx + 1;

        const lineContentEl = document.createElement('div');
        lineContentEl.className = 'line-content';
        lineContentEl.textContent = lineText;

        lineEl.appendChild(lineNumEl);
        lineEl.appendChild(lineContentEl);

        if (aiToChunkMap[idx] !== undefined) {
            lineEl.classList.add('clickable-line');
            if (highlightEnabled) {
                lineEl.classList.add('highlight-ai-permanent');
            }

            lineEl.addEventListener('mouseenter', () => {
                const chunkLineIdx = aiToChunkMap[idx];
                state.chunkQuery.hoveredLineIdx = chunkLineIdx;
                drawChunkTabConnectionLines(chunkLineIdx);

                if (codeViewer && codeViewer.children[chunkLineIdx]) {
                    codeViewer.children[chunkLineIdx].classList.add('hover-highlight-from-ai');
                }
            });
            lineEl.addEventListener('mouseleave', () => {
                const chunkLineIdx = aiToChunkMap[idx];
                state.chunkQuery.hoveredLineIdx = null;
                clearChunkTabConnectionLines();

                if (codeViewer && codeViewer.children[chunkLineIdx]) {
                    codeViewer.children[chunkLineIdx].classList.remove('hover-highlight-from-ai');
                }
            });
        }

        codeWrapper.appendChild(lineEl);
    });

    codeContainer.appendChild(codeWrapper);
    card.appendChild(codeContainer);

    return card;
}

function showLineTooltipForChunk(lineIdx, event) {
    const detail = state.chunkQuery.activeChunkDetail;
    if (!detail || !detail.matchedMessages) return;

    const matchingMsgs = detail.matchedMessages.filter(msg => {
        const indices = msg.contributedLineIndices || [];
        return indices.includes(lineIdx);
    });

    if (matchingMsgs.length === 0) return;

    hoverTooltip.innerHTML = '';

    const title = document.createElement('div');
    title.className = 'tooltip-title';
    title.textContent = `此行属于 AI 贡献 (Row ${detail.startLine + lineIdx})`;
    hoverTooltip.appendChild(title);

    matchingMsgs.forEach(msg => {
        const line = document.createElement('div');
        line.className = 'tooltip-line';
        line.innerHTML = `• Msg <strong class="color-strict">${msg.messageId}</strong>: 匹配度 ${(msg.score * 100).toFixed(0)}% (${msg.matchType})`;
        hoverTooltip.appendChild(line);
    });

    hoverTooltip.classList.remove('hidden');
}

function drawChunkTabConnectionLines(chunkLineIdx) {
    clearChunkTabConnectionLines();
}

function clearChunkTabConnectionLines() {
    const svg = document.getElementById('chunk-tab-connection-svg');
    if (svg) {
        svg.innerHTML = '';
    }
}

