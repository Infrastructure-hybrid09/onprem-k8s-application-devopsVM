(() => {
  "use strict";

  const storageKey = "neuroplan-mvp-demo-v2";
  const sessionHintKey = "neuroplan-session-hint-v1";
  const apiConfig = { enabled: false, baseUrl: "/api", ...(window.NEUROPLAN_API || {}) };
  const fallbackSubjects = [
    { id: 1, code: "LINUX", name: "Linux" },
    { id: 2, code: "NETWORK", name: "Network" },
    { id: 3, code: "KUBERNETES", name: "Kubernetes" },
    { id: 4, code: "DATABASE", name: "Database" },
    { id: 5, code: "CLOUD", name: "Cloud" },
    { id: 6, code: "DEVOPS", name: "DevOps" }
  ];
  const subjectDescriptions = {
    LINUX: "서버 운영 기초",
    NETWORK: "통신과 보안",
    KUBERNETES: "컨테이너 오케스트레이션",
    DATABASE: "SQL과 데이터 모델링",
    CLOUD: "클라우드 인프라",
    DEVOPS: "CI/CD와 자동화"
  };
  const levelToApi = { "초급": "BEGINNER", "중급": "INTERMEDIATE", "고급": "ADVANCED" };
  const defaultState = {
    authenticated: false,
    userName: "",
    userEmail: "",
    isAdmin: false,
    subjects: [],
    subjectLevels: {},
    activeSubjectCode: "",
    quizSubjectCode: "",
    plans: [],
    planId: null,
    planSteps: [],
    planGenerated: false,
    tasks: [false, false, false],
    quizFinished: false,
    quizCorrect: 0,
    quizTotal: 0,
    stats: { solvedCount: 0, correctCount: 0, completedStepCount: 0 },
    dashboard: {
      weekly: { solvedCount: 0, correctCount: 0, completedStepCount: 0 },
      dailyStats: [], subjectStats: [], streakDays: 0, unresolvedWrongNotes: 0
    },
    wrongNotes: [],
    planHistory: [],
    ai: {
      preferences: { enabled: false, consentAt: null, explanationStyle: "BRIEF", availableMinutes: 30 },
      quota: null,
      feedbackByQuestion: {},
      recommendations: [],
      planRationale: {}
    }
  };
  const fallbackQuestions = [
    {
      id: 1, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "동일한 애플리케이션 Pod의 복제본 수를 유지하는 리소스는 무엇인가요?",
      options: [{ id: 11, text: "Service" }, { id: 12, text: "ReplicaSet" }, { id: 13, text: "ConfigMap" }, { id: 14, text: "Namespace" }],
      correctOptionId: 12,
      explanation: "ReplicaSet은 지정한 수의 Pod 복제본이 계속 실행되도록 유지합니다."
    },
    {
      id: 2, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "여러 Pod에 하나의 고정 접근 지점을 제공하는 리소스는 무엇인가요?",
      options: [{ id: 21, text: "Service" }, { id: 22, text: "Secret" }, { id: 23, text: "CronJob" }, { id: 24, text: "PersistentVolume" }],
      correctOptionId: 21,
      explanation: "Service는 변경될 수 있는 Pod 집합 앞에 안정적인 가상 IP와 DNS 이름을 제공합니다."
    },
    {
      id: 3, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "선언적 배포와 롤링 업데이트를 관리하는 리소스는 무엇인가요?",
      options: [{ id: 31, text: "Deployment" }, { id: 32, text: "Node" }, { id: 33, text: "Namespace" }, { id: 34, text: "EndpointSlice" }],
      correctOptionId: 31,
      explanation: "Deployment는 ReplicaSet을 통해 Pod 배포와 롤링 업데이트를 관리합니다."
    },
    {
      id: 4, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "민감하지 않은 설정값을 키-값으로 저장하는 리소스는 무엇인가요?",
      options: [{ id: 41, text: "ConfigMap" }, { id: 42, text: "DaemonSet" }, { id: 43, text: "IngressClass" }, { id: 44, text: "Job" }],
      correctOptionId: 41,
      explanation: "ConfigMap은 애플리케이션 설정을 컨테이너 이미지와 분리해 저장합니다."
    },
    {
      id: 5, subjectName: "Kubernetes", difficulty: "BEGINNER",
      text: "컨테이너 워크로드가 실행되는 Kubernetes의 최소 배포 단위는 무엇인가요?",
      options: [{ id: 51, text: "Pod" }, { id: 52, text: "ServiceAccount" }, { id: 53, text: "Role" }, { id: 54, text: "StorageClass" }],
      correctOptionId: 51,
      explanation: "Pod는 Kubernetes에서 하나 이상의 컨테이너를 실행하는 최소 배포 단위입니다."
    }
  ];

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const escapeHtml = value => String(value ?? "").replace(/[&<>'"]/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);

  let state = loadState();
  let subjectCatalog = [...fallbackSubjects];
  let authMode = "signup";
  let draftSubjects = [];
  let draftSubjectLevels = {};
  let questions = [];
  let quizAnswers = [];
  let quizIndex = 0;
  let quizScore = 0;
  let chosenAnswer = null;
  let answerChecked = false;
  let quizMode = "BANK";
  let aiQuizRunId = null;
  let toastTimer;
  let adminOverview = null;
  let adminSubjects = [];
  let adminSubjectStats = [];
  let adminQuestions = [];
  let adminUserPage = { content: [], totalElements: 0, page: 0, size: 20 };
  let adminPageIndex = 0;
  const adminPageSize = 20;
  let editingAdminQuestionId = null;
  let adminDeleteTarget = null;
  let accountDetails = null;
  let accountDirty = false;
  let activePage = "dashboard";
  let reauthExpiresAt = 0;
  let pendingSecureAction = null;
  let pendingAiAction = null;
  let aiLoadingHideTimer = null;
  let aiLoadingStartedAt = 0;
  let refreshSessionPromise = null;
  const aiLoadingMinDurationMs = 800;
  const seoulTimeZone = "Asia/Seoul";

  function setAiLoading(active, title = "AI가 학습 내용을 준비하고 있어요") {
    const overlay = $("#aiLoadingOverlay");
    clearTimeout(aiLoadingHideTimer);
    $("#aiLoadingTitle").textContent = title;
    $("#aiLoadingMessage").textContent = "보통 수 초, 최대 120초가 걸릴 수 있습니다.";
    if (active) {
      aiLoadingStartedAt = performance.now();
      overlay.classList.add("is-visible");
      overlay.removeAttribute("aria-hidden");
      document.body.setAttribute("aria-busy", "true");
      return;
    }
    const elapsed = performance.now() - aiLoadingStartedAt;
    const hide = () => {
      overlay.classList.remove("is-visible");
      overlay.setAttribute("aria-hidden", "true");
      document.body.setAttribute("aria-busy", "false");
    };
    if (elapsed < aiLoadingMinDurationMs) {
      aiLoadingHideTimer = setTimeout(hide, aiLoadingMinDurationMs - elapsed);
      return;
    }
    hide();
  }

  function showAiLoading(title) {
    setAiLoading(true, title);
    return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => {
      setTimeout(resolve, 80);
    })));
  }

  function planStepContentHtml(content) {
    const normalized = String(content ?? "").replace(/\r/g, "").trim();
    if (!normalized) return '<p class="plan-step-summary">학습 내용을 준비하고 있습니다.</p>';

    const lines = normalized
      // AI가 `1)`, `1.`, `①` 또는 실제 줄바꿈을 섞어 반환해도
      // 각 실습 행동을 별도 목록 항목으로 표시한다.
      .replace(/[\s\u00a0]+(?=(?:\d{1,2}[.)]|[①-⑳])\s*)/g, "\n")
      .split(/\n+/)
      .map(value => value.trim())
      .filter(Boolean);
    const intro = [];
    const actions = [];
    lines.forEach(line => {
      const numbered = line.match(/^(?:\d{1,2}[.)]|[①-⑳])\s*(.+)$/s);
      if (numbered) actions.push(numbered[1].trim());
      else if (actions.length) actions[actions.length - 1] = `${actions[actions.length - 1]} ${line}`.trim();
      else intro.push(line);
    });

    if (!actions.length && lines.length > 1) {
      actions.push(...lines);
      intro.length = 0;
    }

    if (!actions.length) return `<p class="plan-step-summary">${escapeHtml(normalized)}</p>`;
    return [
      intro.length ? `<p class="plan-step-intro">${escapeHtml(intro.join(" "))}</p>` : "",
      `<ol class="plan-step-actions" aria-label="실습 순서">${actions.map(action => `<li>${escapeHtml(action)}</li>`).join("")}</ol>`
    ].join("");
  }

  function loadState() {
    try {
      const saved = JSON.parse(localStorage.getItem(storageKey) || "{}");
      return {
        ...defaultState,
        ...saved,
        subjectLevels: { ...(saved.subjectLevels || {}) },
        tasks: Array.isArray(saved.tasks) ? saved.tasks.slice(0, 3) : [false, false, false],
        stats: { ...defaultState.stats, ...(saved.stats || {}) },
        dashboard: { ...defaultState.dashboard, ...(saved.dashboard || {}) },
        wrongNotes: Array.isArray(saved.wrongNotes) ? saved.wrongNotes : [],
        plans: Array.isArray(saved.plans) ? saved.plans : [],
        planHistory: Array.isArray(saved.planHistory) ? saved.planHistory : [],
        ai: {
          ...defaultState.ai,
          ...(saved.ai || {}),
          feedbackByQuestion: { ...(saved.ai?.feedbackByQuestion || {}) },
          recommendations: Array.isArray(saved.ai?.recommendations) ? saved.ai.recommendations : [],
          planRationale: { ...(saved.ai?.planRationale || {}) }
        }
      };
    } catch (_) {
      return { ...defaultState, tasks: [false, false, false] };
    }
  }

  function saveState() {
    if (!apiConfig.enabled) localStorage.setItem(storageKey, JSON.stringify(state));
  }

  function applySessionHint() {
    if (!apiConfig.enabled) return;
    try {
      const hint = JSON.parse(localStorage.getItem(sessionHintKey) || "null");
      if (!hint?.nickname || !hint?.email) return;
      state.authenticated = true;
      state.userName = hint.nickname;
      state.userEmail = hint.email;
    } catch (_) {
      localStorage.removeItem(sessionHintKey);
    }
  }

  function saveSessionHint() {
    if (!apiConfig.enabled || !state.authenticated) return;
    localStorage.setItem(sessionHintKey, JSON.stringify({
      nickname: state.userName,
      email: state.userEmail
    }));
  }

  function subjectByCode(code) {
    return subjectCatalog.find(subject => subject.code === code) || { code, name: code };
  }

  function subjectName(code) {
    return subjectByCode(code).name;
  }

  function hasCompleteProfile(profile = state) {
    return profile.subjects.length > 0 && profile.subjects.every(code => Boolean(profile.subjectLevels[code]));
  }

  function profileLabel(profile = state) {
    return profile.subjects.map(code => `${subjectName(code)} · ${profile.subjectLevels[code]}`).join(", ");
  }

  function clearAuthFields() {
    $("#authForm").reset();
    $("#authName").value = "";
    $("#authEmail").value = "";
    $("#authPassword").value = "";
    $("#authMessage").textContent = "";
  }

  function openModal(id) {
    if (id === "authModal") {
      clearAuthFields();
      setTimeout(clearAuthFields, 0);
    }
    const modal = document.getElementById(id);
    modal.hidden = false;
    const firstInput = modal.querySelector("input, button");
    if (firstInput) setTimeout(() => firstInput.focus(), 0);
  }

  function closeModal(id) {
    document.getElementById(id).hidden = true;
    if (id === "authModal") clearAuthFields();
    if (id === "reauthModal") {
      $("#reauthForm").reset();
      $("#reauthMessage").textContent = "";
      pendingSecureAction = null;
    }
    if (id === "adminDeleteModal") {
      $("#adminDeleteForm").reset();
      $("#adminDeleteMessage").textContent = "";
      adminDeleteTarget = null;
    }
    if (id === "aiConsentModal") {
      $("#aiConsentMessage").textContent = "";
      pendingAiAction = null;
    }
  }

  function openUserMenu() {
    if (!state.authenticated) return;
    $("#userDropdown").hidden = false;
    $("#userMenuScrim").hidden = false;
    $("#userMenuButton").setAttribute("aria-expanded", "true");
  }

  function closeUserMenu({ restoreFocus = false } = {}) {
    $("#userDropdown").hidden = true;
    $("#userMenuScrim").hidden = true;
    $("#userMenuButton").setAttribute("aria-expanded", "false");
    if (restoreFocus && state.authenticated) $("#userMenuButton").focus();
  }

  function hasValidReauth() {
    return reauthExpiresAt > Date.now() + 1000;
  }

  function updateReauthStatus() {
    const target = $("#reauthStatus");
    if (!target) return;
    if (!hasValidReauth()) {
      target.textContent = "보안 확인 필요";
      return;
    }
    const seconds = Math.max(0, Math.ceil((reauthExpiresAt - Date.now()) / 1000));
    target.textContent = `보안 확인 ${Math.ceil(seconds / 60)}분 남음`;
  }

  async function requireReauth(action) {
    if (!hasValidReauth()) {
      pendingSecureAction = action;
      $("#reauthForm").reset();
      $("#reauthMessage").textContent = "";
      openModal("reauthModal");
      return undefined;
    }
    try {
      return await action();
    } catch (error) {
      if (error.status === 401) {
        reauthExpiresAt = 0;
        updateReauthStatus();
        pendingSecureAction = action;
        $("#reauthForm").reset();
        $("#reauthMessage").textContent = "보안 확인이 만료되었습니다. 비밀번호를 다시 입력해 주세요.";
        openModal("reauthModal");
        return undefined;
      }
      throw error;
    }
  }

  function resetAuthenticatedState() {
    state = {
      ...defaultState,
      tasks: [false, false, false],
      ai: { ...defaultState.ai, feedbackByQuestion: {}, recommendations: [], planRationale: {} }
    };
    accountDetails = null;
    accountDirty = false;
    reauthExpiresAt = 0;
    pendingSecureAction = null;
    pendingAiAction = null;
    adminOverview = null;
    localStorage.removeItem(storageKey);
    localStorage.removeItem(sessionHintKey);
    closeUserMenu();
  }

  async function logout() {
    closeUserMenu();
    try {
      if (apiConfig.enabled) await apiRequest("/auth/logout", { method: "POST" });
    } catch (error) {
      toast(`로그아웃 요청 확인이 필요합니다: ${error.message}`);
    } finally {
      resetAuthenticatedState();
      clearAuthFields();
      showLearningPage();
      updateUI();
      toast("로그아웃했습니다.");
    }
  }

  function showLearningPage() {
    showLearningShell();
    activePage = "dashboard";
    showPage("dashboard");
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function showLearningShell() {
    $("#adminPage").hidden = true;
    $("#dashboard").hidden = false;
    $("#categoryNav").hidden = false;
  }

  async function showPage(page) {
    if (!state.authenticated && page !== "dashboard") {
      setAuthMode("login");
      openModal("authModal");
      toast("로그인 후 이용할 수 있습니다.");
      return;
    }
    if (activePage === "account" && page !== "account" && accountDirty) {
      if (!confirm("저장하지 않은 계정 설정 변경사항이 있습니다. 이동할까요?")) return;
      accountDirty = false;
    }
    // The admin view uses a separate page container. Restore the regular
    // learning shell before activating account or category content.
    showLearningShell();
    activePage = page;
    $$('[data-page-section]').forEach(section => { section.hidden = section.dataset.pageSection !== page; });
    $$('[data-page]').forEach(button => button.classList.toggle("active", button.dataset.page === page));
    if (page === "history") await loadPlanHistory();
    if (page === "account") updateReauthStatus();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function loadAccountDetails() {
    accountDetails = apiConfig.enabled
      ? await apiRequest("/auth/account")
      : {
          user: {
            email: state.userEmail,
            nickname: state.userName,
            accountStatus: "ACTIVE",
            createdAt: new Date().toISOString()
          },
          activeSessionCount: 1
        };
    state.userName = accountDetails.user.nickname;
    state.userEmail = accountDetails.user.email;
    $("#accountEmail").textContent = accountDetails.user.email;
    $("#accountStatus").textContent = accountDetails.user.accountStatus;
    $("#accountCreatedAt").textContent = formatDateTime(accountDetails.user.createdAt);
    $("#accountSessionCount").textContent = `${accountDetails.activeSessionCount}개`;
    $("#accountNickname").value = accountDetails.user.nickname;
    $("#nicknameMessage").textContent = "";
    $("#passwordMessage").textContent = "";
    accountDirty = false;
    updateReauthStatus();
  }

  async function openAccountSettings() {
    closeUserMenu();
    await requireReauth(async () => {
      await loadAccountDetails();
      await showPage("account");
      updateUI();
    });
  }

  async function loadAdminOverview() {
    if (!state.isAdmin) return null;
    if (apiConfig.enabled) {
      const query = encodeURIComponent($("#adminUserQuery")?.value.trim() || "");
      const status = encodeURIComponent($("#adminUserStatus")?.value || "");
      const [overview, userPage, stats, subjects, questions] = await Promise.all([
        apiRequest("/admin/overview"),
        apiRequest(`/admin/users?query=${query}&status=${status}&page=${adminPageIndex}&size=${adminPageSize}`),
        apiRequest("/admin/statistics/subjects"),
        apiRequest("/admin/subjects"),
        apiRequest("/admin/questions")
      ]);
      adminUserPage = userPage;
      adminOverview = { ...overview, recentUsers: userPage.content };
      adminSubjectStats = stats;
      adminSubjects = subjects;
      adminQuestions = questions;
    } else {
      adminOverview = {
          adminUserId: 1,
          adminEmail: state.userEmail,
          users: { total: 1, active: 1, locked: 0, withdrawn: 0 },
          activeSubjects: subjectCatalog.length,
          todayPlans: state.planGenerated ? 1 : 0,
          todayAttempts: state.quizFinished ? 1 : 0,
          recentUsers: [{
            id: 1, email: state.userEmail, nickname: state.userName,
            accountStatus: "ACTIVE", subjectCount: state.subjects.length,
            createdAt: new Date().toISOString(), lastSessionAt: new Date().toISOString()
          }]
        };
      adminUserPage = { content: adminOverview.recentUsers, totalElements: 1, page: 0, size: adminPageSize };
      adminSubjectStats = [];
      adminSubjects = subjectCatalog.map(subject => ({ ...subject, active: true }));
      adminQuestions = [];
    }
    renderAdminOverview();
    return adminOverview;
  }

  async function detectAdmin() {
    if (!state.authenticated) {
      state.isAdmin = false;
      return;
    }
    if (!apiConfig.enabled) {
      state.isAdmin = state.userEmail.toLowerCase() === "admin@nplan.local";
      return;
    }
    try {
      adminOverview = await apiRequest("/admin/overview");
      state.isAdmin = true;
    } catch (error) {
      if (error.status !== 403) throw error;
      state.isAdmin = false;
      adminOverview = null;
    }
  }

  function formatDateTime(value) {
    if (!value) return "-";
    const text = String(value).trim();
    const hasTimeZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text);
    const parsed = new Date(hasTimeZone ? text : `${text}Z`);
    if (Number.isNaN(parsed.getTime())) return String(value);
    const formatted = parsed.toLocaleString("ko-KR", {
          timeZone: seoulTimeZone,
          year: "numeric",
          month: "2-digit",
          day: "2-digit",
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hour12: false
        });
    return `${formatted} KST`;
  }

  function renderAdminOverview() {
    if (!adminOverview) return;
    $("#adminIdentity").textContent = `${adminOverview.adminEmail} 관리자 · 활성 과목 ${adminOverview.activeSubjects}개`;
    $("#adminTotalUsers").textContent = String(adminOverview.users.total);
    $("#adminActiveUsers").textContent = String(adminOverview.users.active);
    $("#adminTodayPlans").textContent = String(adminOverview.todayPlans);
    $("#adminTodayAttempts").textContent = String(adminOverview.todayAttempts);
    $("#adminUsersBody").innerHTML = adminOverview.recentUsers.length
      ? adminOverview.recentUsers.map(user => `
          <tr>
            <td><strong>${escapeHtml(user.nickname)}</strong><br><span>${escapeHtml(user.email)}</span></td>
            <td><span class="account-badge ${user.accountStatus.toLowerCase()}">${escapeHtml(user.accountStatus)}</span></td>
            <td>${user.subjectCount}</td>
            <td>${escapeHtml(formatDateTime(user.createdAt))}</td>
            <td>${escapeHtml(formatDateTime(user.lastSessionAt))}</td>
            <td>${user.id === adminOverview.adminUserId
              ? '<span class="account-badge active">현재 관리자</span>'
              : `<div class="admin-user-actions">
                  <button class="button secondary" type="button" data-user-learning="${user.id}">학습현황</button>
                  <button class="button secondary" type="button" data-admin-status="ACTIVE" data-user-id="${user.id}">활성</button>
                  <button class="button secondary" type="button" data-admin-status="LOCKED" data-user-id="${user.id}">잠금</button>
                  <button class="button secondary" type="button" data-admin-status="WITHDRAWN" data-user-id="${user.id}">탈퇴</button>
                  ${user.accountStatus === "WITHDRAWN" ? `<button class="button danger" type="button" data-admin-delete="${user.id}">영구 삭제</button>` : ""}
                </div>`}</td>
          </tr>`).join("")
      : '<tr><td colspan="6">표시할 회원이 없습니다.</td></tr>';
    const totalPages = Math.max(1, Math.ceil((adminUserPage.totalElements || 0) / adminPageSize));
    $("#adminPageLabel").textContent = `${adminPageIndex + 1} / ${totalPages}`;
    $("#adminPrevPage").disabled = adminPageIndex <= 0;
    $("#adminNextPage").disabled = adminPageIndex + 1 >= totalPages;
    $("#adminSubjectStats").innerHTML = adminSubjectStats.length
      ? adminSubjectStats.map(item => {
          const rate = item.solvedCount ? Math.round((item.correctCount / item.solvedCount) * 100) : 0;
          return `<div class="subject-stat-row"><div><strong>${escapeHtml(item.subjectName)}</strong><div class="subject-stat-track"><span style="width:${rate}%"></span></div><span class="metric-caption">학습자 ${item.learnerCount}명 · 풀이 ${item.solvedCount}개</span></div><em>${rate}%</em></div>`;
        }).join("")
      : '<span class="metric-caption">집계된 과목 통계가 없습니다.</span>';
    $("#adminQuestionSubject").innerHTML = adminSubjects.filter(item => item.active).map(item => `<option value="${item.id}">${escapeHtml(item.name)} (${escapeHtml(item.code)})</option>`).join("");
    $("#adminSubjectList").innerHTML = adminSubjects.map(item => `<article class="history-item"><div><strong>${escapeHtml(item.name)}</strong><p>${escapeHtml(item.code)} · ${item.active ? "활성" : "비활성"}</p></div><button class="button secondary small" type="button" data-admin-subject-active="${item.id}" data-next-active="${!item.active}">${item.active ? "비활성화" : "활성화"}</button></article>`).join("");
    $("#adminQuestionList").innerHTML = adminQuestions.map(item => `<article class="history-item"><div><strong>#${item.questionNo} ${escapeHtml(item.questionText)}</strong><p>${escapeHtml(item.difficulty)} · ${item.active ? "출제 중" : "비활성"}</p></div><div class="admin-user-actions"><button class="button secondary small" type="button" data-admin-question-edit="${item.id}">수정</button><button class="button secondary small" type="button" data-admin-question-active="${item.id}" data-next-active="${!item.active}">${item.active ? "비활성화" : "활성화"}</button></div></article>`).join("") || '<span class="metric-caption">등록된 문제가 없습니다.</span>';
  }

  function resetAdminQuestionForm() {
    editingAdminQuestionId = null;
    $("#adminQuestionForm").reset();
    $("#adminQuestionSubmit").textContent = "문제 등록";
    $("#adminQuestionCancel").hidden = true;
    delete $("#adminQuestionForm").dataset.editingActive;
  }

  async function editAdminQuestion(questionId) {
    const detail = await apiRequest(`/admin/questions/${questionId}`);
    editingAdminQuestionId = detail.id;
    $("#adminQuestionSubject").value = String(detail.subjectId);
    $("#adminQuestionNo").value = String(detail.questionNo);
    $("#adminQuestionText").value = detail.questionText;
    $("#adminQuestionOptions").value = detail.options
      .map(option => `${option.correct ? "*" : ""}${option.text}`)
      .join("\n");
    $("#adminQuestionExplanation").value = detail.explanation;
    $("#adminQuestionForm").dataset.editingActive = String(detail.active);
    $("#adminQuestionSubmit").textContent = "문제 수정 저장";
    $("#adminQuestionCancel").hidden = false;
    $("#adminQuestionForm").scrollIntoView({ behavior: "smooth", block: "center" });
    $("#adminQuestionText").focus();
  }

  async function openAdminPage() {
    if (!state.isAdmin) return;
    closeUserMenu();
    $("#userMenuButton").disabled = true;
    try {
      await loadAdminOverview();
      $("#dashboard").hidden = true;
      $("#categoryNav").hidden = true;
      $("#adminPage").hidden = false;
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (error) {
      toast(error.message);
    } finally {
      $("#userMenuButton").disabled = false;
    }
  }

  function toast(message) {
    clearTimeout(toastTimer);
    $("#toastMessage").textContent = message;
    $("#toast").classList.add("show");
    toastTimer = setTimeout(() => $("#toast").classList.remove("show"), 2800);
  }

  function isAiRequest(path) {
    return path.startsWith("/ai/");
  }

  function requestTimeoutMs(path) {
    return isAiRequest(path) ? 125000 : 20000;
  }

  async function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const request = { ...options };
    if (!request.signal) request.signal = controller.signal;
    try {
      return await fetch(url, request);
    } finally {
      clearTimeout(timer);
    }
  }

  async function refreshSession() {
    if (!refreshSessionPromise) {
      refreshSessionPromise = (async () => {
        let response;
        try {
          response = await fetchWithTimeout(`${apiConfig.baseUrl}/auth/refresh`, {
            method: "POST",
            credentials: "include",
            cache: "no-store",
            headers: { "Content-Type": "application/json" }
          }, 15000);
        } catch (error) {
          throw normalizeRequestError(error, "/auth/refresh");
        }
        if (!response.ok) {
          let message = "로그인 세션을 갱신하지 못했습니다.";
          try {
            const payload = await response.json();
            if (payload?.message) message = payload.message;
          } catch (_) {
            // JSON 오류 본문이 아니어도 상태 코드로 세션 만료를 전달합니다.
          }
          const error = new Error(message);
          error.status = response.status;
          throw error;
        }
        return response;
      })().finally(() => {
        refreshSessionPromise = null;
      });
    }
    return refreshSessionPromise;
  }

  function normalizeRequestError(error, path) {
    const normalized = error instanceof Error ? error : new Error("서버 응답을 받지 못했습니다.");
    normalized.network = true;
    normalized.status = normalized.status || 0;
    if (error?.name === "AbortError") {
      normalized.message = isAiRequest(path)
        ? "AI 응답 시간이 초과되었습니다. 잠시 후 새로고침해 저장된 플랜을 확인해 주세요."
        : "요청 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
    } else if (error instanceof TypeError || !error) {
      normalized.message = isAiRequest(path)
        ? "AI 서버 응답을 받지 못했습니다. 네트워크 또는 게이트웨이를 확인해 주세요."
        : "서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.";
    } else if (error instanceof SyntaxError) {
      normalized.message = isAiRequest(path)
        ? "AI 응답이 중간에 끊겼습니다. 잠시 후 저장된 플랜과 토큰 잔량을 확인해 주세요."
        : "서버 응답 형식을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    }
    return normalized;
  }

  async function apiRequest(path, options = {}, allowRefresh = true) {
    let response;
    try {
      response = await fetchWithTimeout(`${apiConfig.baseUrl}${path}`, {
        credentials: "include",
        cache: "no-store",
        ...options,
        headers: { "Content-Type": "application/json", ...(options.headers || {}) }
      }, requestTimeoutMs(path));
    } catch (error) {
      throw normalizeRequestError(error, path);
    }
    let payload;
    try {
      const contentType = response.headers.get("content-type") || "";
      payload = contentType.includes("application/json")
        ? await response.json()
        : { message: await response.text() };
    } catch (error) {
      throw normalizeRequestError(error, path);
    }
    const refreshExcluded = ["/auth/login", "/auth/signup", "/auth/refresh", "/auth/reauth"].includes(path);
    if (response.status === 401 && allowRefresh && !refreshExcluded) {
      try {
        await refreshSession();
        return apiRequest(path, options, false);
      } catch (refreshError) {
        if (isAiRequest(path)) {
          refreshError.message = "AI 요청 중 로그인 세션을 갱신하지 못했습니다. 현재 화면은 유지됩니다.";
        }
        throw refreshError;
      }
    }
    if (!response.ok) {
      const error = new Error(payload.message || `요청에 실패했습니다. (HTTP ${response.status})`);
      error.status = response.status;
      // Gateway timeout/5xx may occur after the backend has already charged and
      // persisted an AI result. Treat these as recoverable network failures so
      // the caller refreshes learning state before showing the final message.
      error.network = response.status >= 502;
      throw error;
    }
    return payload;
  }

  async function handleAiRequestError(error) {
    if (!error?.network || !apiConfig.enabled) {
      toast(error.message);
      return;
    }
    try {
      await loadLearningState();
      updateUI();
    } catch (_) {
      // 응답이 끊긴 경우에도 저장 상태 확인을 시도한 뒤 원래 오류를 안내합니다.
    }
    toast(`${error.message} 저장된 결과를 확인했습니다.`);
  }

  async function loadAiState() {
    if (!apiConfig.enabled || !state.authenticated) return;
    try {
      const [preferences, quota, feedback, recommendations] = await Promise.all([
        apiRequest("/ai/preferences"),
        apiRequest("/ai/quota"),
        apiRequest("/ai/wrong-notes/feedback"),
        apiRequest("/ai/recommendations")
      ]);
      state.ai = {
        ...state.ai,
        preferences,
        quota,
        feedbackByQuestion: Object.fromEntries((feedback || []).map(item => [String(item.questionId), item])),
        recommendations: Array.isArray(recommendations) ? recommendations : []
      };
    } catch (error) {
      state.ai = { ...state.ai, quota: null };
      console.warn("AI 상태를 불러오지 못했습니다.", error);
    }
  }

  function openAiSettings(action = null) {
    const preference = state.ai.preferences || defaultState.ai.preferences;
    pendingAiAction = action;
    $("#aiExplanationStyle").value = preference.explanationStyle || "BRIEF";
    $("#aiAvailableMinutes").value = String(preference.availableMinutes || 30);
    $("#aiConsentCheck").checked = Boolean(preference.consentAt || preference.enabled);
    $("#aiConsentMessage").textContent = "";
    openModal("aiConsentModal");
  }

  async function ensureAiEnabled(action) {
    if (!apiConfig.enabled) return action();
    const preference = state.ai.preferences || defaultState.ai.preferences;
    if (preference.enabled && preference.consentAt) return action();
    openAiSettings(action);
    return undefined;
  }

  function updateAiQuota(quota) {
    if (quota) state.ai = { ...state.ai, quota };
  }

  function applyProfile(profile = []) {
    state.subjects = profile.map(item => item.subjectCode);
    state.subjectLevels = Object.fromEntries(profile.map(item => [item.subjectCode, item.levelLabel]));
    if (!state.subjects.includes(state.activeSubjectCode)) state.activeSubjectCode = state.subjects[0] || "";
    if (!state.subjects.includes(state.quizSubjectCode)) state.quizSubjectCode = state.subjects[0] || "";
  }

  function applyPlan(plan) {
    state.planId = plan?.id || null;
    state.planGenerated = Boolean(plan);
    state.planSteps = plan?.steps || [];
    state.tasks = [1, 2, 3].map(stepNo => {
      const step = state.planSteps.find(item => item.stepNo === stepNo);
      return step?.status === "COMPLETED";
    });
  }

  function selectActivePlan(code) {
    state.activeSubjectCode = code || state.subjects[0] || "";
    applyPlan((state.plans || []).find(plan => plan.subjectCode === state.activeSubjectCode) || null);
  }

  function applyPlans(plans = []) {
    state.plans = Array.isArray(plans) ? plans : [];
    selectActivePlan(state.activeSubjectCode);
  }

  async function loadLearningState() {
    const [subjects, learning, dashboard, wrongNotes] = await Promise.all([
      apiRequest("/learning/subjects"),
      apiRequest("/learning/state"),
      apiRequest("/learning/dashboard?days=28"),
      apiRequest("/learning/wrong-notes")
    ]);
    subjectCatalog = subjects;
    applyProfile(learning.profile);
    applyPlans(learning.plans || (learning.plan ? [learning.plan] : []));
    state.stats = learning.stats || { solvedCount: 0, correctCount: 0, completedStepCount: 0 };
    state.quizFinished = Boolean(learning.diagnosis);
    state.quizCorrect = learning.diagnosis?.correctAnswers || 0;
    state.quizTotal = learning.diagnosis?.totalQuestions || 0;
    state.dashboard = { ...defaultState.dashboard, ...(dashboard || {}) };
    state.wrongNotes = Array.isArray(wrongNotes) ? wrongNotes : [];
    renderSubjectChoices();
    await loadAiState();
  }

  async function loadPlanHistory() {
    if (!state.authenticated) return;
    try {
      state.planHistory = apiConfig.enabled
        ? await apiRequest("/learning/plans/history?days=30")
        : state.planHistory;
      renderPlanHistory();
    } catch (error) {
      toast(error.message);
    }
  }

  async function restoreSession() {
    if (!apiConfig.enabled) {
      await detectAdmin();
      renderSubjectChoices();
      updateUI();
      return;
    }
    let payload;
    try {
      payload = await apiRequest("/auth/me");
    } catch (error) {
      if (error.status === 401) resetAuthenticatedState();
      else toast(`세션 확인이 지연되고 있습니다: ${error.message}`);
      updateUI();
      return;
    }

    state.authenticated = true;
    state.userName = payload.user.nickname;
    state.userEmail = payload.user.email;
    saveSessionHint();
    updateUI();

    try {
      await loadLearningState();
    } catch (error) {
      toast(`로그인은 유지되지만 학습 데이터 일부를 불러오지 못했습니다: ${error.message}`);
    }
    try {
      await detectAdmin();
    } catch (error) {
      state.isAdmin = false;
      console.warn("관리자 권한을 확인하지 못했습니다.", error);
    }
    updateUI();
  }

  function renderSubjectChoices() {
    if (!subjectCatalog.length) {
      $("#subjectChoices").innerHTML = '<div class="subject-level-empty">활성 과목이 없습니다. DB의 subjects 데이터를 확인해 주세요.</div>';
      return;
    }
    $("#subjectChoices").innerHTML = subjectCatalog.map(subject => `
      <button class="choice${draftSubjects.includes(subject.code) ? " selected" : ""}" type="button" data-subject="${escapeHtml(subject.code)}">
        <strong>${escapeHtml(subject.name)}</strong><span>${escapeHtml(subjectDescriptions[subject.code] || "맞춤 학습")}</span>
      </button>`).join("");
  }

  function renderSubjectLevelSettings() {
    if (!draftSubjects.length) {
      $("#subjectLevelSettings").innerHTML = '<div class="subject-level-empty">과목을 선택하면 과목별 수준 설정이 나타납니다.</div>';
      return;
    }
    const descriptions = { "초급": "개념부터", "중급": "실습 중심", "고급": "설계 중심" };
    $("#subjectLevelSettings").innerHTML = draftSubjects.map(code => `
      <section class="subject-level-row" aria-label="${escapeHtml(subjectName(code))} 수준 설정">
        <div class="subject-level-head"><strong>${escapeHtml(subjectName(code))}</strong><span>${draftSubjectLevels[code] ? `${draftSubjectLevels[code]} 선택됨` : "수준을 선택해 주세요"}</span></div>
        <div class="subject-level-buttons">
          ${["초급", "중급", "고급"].map(level => `
            <button class="level-choice${draftSubjectLevels[code] === level ? " selected" : ""}" type="button" data-level-subject="${escapeHtml(code)}" data-level="${level}">
              <strong>${level}</strong><span>${descriptions[level]}</span>
            </button>`).join("")}
        </div>
      </section>`).join("");
  }

  function renderSubjectTabs() {
    const tabs = state.subjects.map(code => `
      <button class="plan-subject-tab${state.activeSubjectCode === code ? " active" : ""}" type="button" data-plan-subject="${escapeHtml(code)}">
        ${escapeHtml(subjectName(code))} · ${escapeHtml(state.subjectLevels[code])}
      </button>`).join("");
    $("#planSubjectTabs").innerHTML = tabs || '<span class="metric-caption">학습 프로필에서 과목을 선택해 주세요.</span>';
    $("#quizSubjectTabs").innerHTML = state.subjects.map(code => `
      <button class="plan-subject-tab${state.quizSubjectCode === code ? " active" : ""}" type="button" data-quiz-subject="${escapeHtml(code)}">
        ${escapeHtml(subjectName(code))}
      </button>`).join("") || '<span class="metric-caption">학습 프로필에서 과목을 선택해 주세요.</span>';
    $("#quizLaunchTitle").textContent = state.quizSubjectCode
      ? `${subjectName(state.quizSubjectCode)} 확인 문제`
      : "과목을 선택해 주세요";
  }

  function renderPlanHistory() {
    $("#planHistoryList").innerHTML = state.planHistory.length
      ? state.planHistory.map(item => `
          <article class="history-item"><div><strong>${escapeHtml(item.subjectName)} · ${escapeHtml(item.title)}</strong><p>${escapeHtml(item.planDate)} · ${escapeHtml(item.status)}</p></div><span class="today-tag">${item.completedSteps}/${item.totalSteps}단계</span></article>`).join("")
      : '<div class="empty-state"><div><strong>아직 학습 기록이 없습니다.</strong><span>플랜을 생성하면 날짜별 기록이 표시됩니다.</span></div></div>';
  }

  function currentStep() {
    if (!state.authenticated) return 1;
    if (!hasCompleteProfile()) return 2;
    if (!state.planGenerated) return 3;
    if (!state.tasks.every(Boolean)) return 4;
    if (!state.quizFinished) return 5;
    return 6;
  }

  function planProgress() {
    return Math.round((state.tasks.filter(Boolean).length / 3) * 100);
  }

  function renderDashboardDetails() {
    const dashboard = { ...defaultState.dashboard, ...(state.dashboard || {}) };
    const weekly = { ...defaultState.dashboard.weekly, ...(dashboard.weekly || {}) };
    const accuracy = weekly.solvedCount
      ? Math.round((weekly.correctCount / weekly.solvedCount) * 100)
      : null;
    $("#weeklySolved").textContent = String(weekly.solvedCount);
    $("#weeklyAccuracy").textContent = accuracy === null ? "-" : `${accuracy}%`;
    $("#streakDays").textContent = `${dashboard.streakDays || 0}일`;

    const activityByDate = new Map((dashboard.dailyStats || []).map(item => [String(item.studyDate), item]));
    const cells = Array.from({ length: 28 }, (_, index) => {
      const date = new Date();
      date.setHours(0, 0, 0, 0);
      date.setDate(date.getDate() - (27 - index));
      const key = date.toISOString().slice(0, 10);
      const item = activityByDate.get(key);
      const activity = (item?.solvedCount || 0) + (item?.completedStepCount || 0);
      const level = activity >= 8 ? 3 : activity >= 4 ? 2 : activity > 0 ? 1 : 0;
      const label = `${key}: 총 활동 ${activity}회 (풀이 ${item?.solvedCount || 0}문제, 완료 ${item?.completedStepCount || 0}단계)`;
      return `<span class="heatmap-cell${level ? ` level-${level}` : ""}" title="${escapeHtml(label)}" aria-label="${escapeHtml(label)}"></span>`;
    });
    $("#studyHeatmap").innerHTML = cells.join("");

    $("#subjectStatList").innerHTML = (dashboard.subjectStats || []).length
      ? dashboard.subjectStats.map(item => {
          const rate = item.solvedCount ? Math.round((item.correctCount / item.solvedCount) * 100) : 0;
          const progress = item.totalSteps ? Math.round((item.completedSteps / item.totalSteps) * 100) : 0;
          return `<div class="subject-stat-row"><div><strong>${escapeHtml(item.subjectName)} · ${escapeHtml(item.learningLevel || "")}</strong><div class="subject-stat-track"><span style="width:${progress}%"></span></div><span class="metric-caption">플랜 ${item.completedSteps}/${item.totalSteps}단계 · 문제 ${item.solvedCount}개 · 정답률 ${rate}%</span></div><em>${progress}%</em></div>`;
        }).join("")
      : '<span class="metric-caption">과목을 설정하면 과목별 진행률이 나타납니다.</span>';
  }

  function renderWrongNotes() {
    const allNotes = state.wrongNotes || [];
    const subjectFilter = $("#wrongSubjectFilter").value;
    const statusFilter = $("#wrongStatusFilter").value;
    const notes = allNotes.filter(note =>
      (!subjectFilter || note.subjectCode === subjectFilter) &&
      (statusFilter === "all" || (statusFilter === "done" ? note.relearned : !note.relearned))
    );
    const pending = allNotes.filter(note => !note.relearned).length;
    $("#wrongNoteCount").textContent = `미해결 ${pending}개`;
    $("#wrongNoteList").innerHTML = notes.length
      ? notes.map(note => {
          const feedback = state.ai.feedbackByQuestion?.[String(note.questionId)];
          return `
          <article class="wrong-note-item${note.relearned ? " relearned" : ""}">
            <div class="wrong-note-top"><div><span class="wrong-note-subject">${escapeHtml(note.subjectName)} · 누적 오답 ${note.wrongCount}회</span><p class="wrong-note-question">${escapeHtml(note.questionText)}</p></div><span class="wrong-note-status${note.relearned ? "" : " pending"}">${note.relearned ? "재학습 완료" : "재학습 필요"}</span></div>
            <div class="wrong-note-answer"><strong>내 답</strong>: ${escapeHtml(note.selectedOptionText || "기록 없음")}<br><strong>정답</strong>: ${escapeHtml(note.correctOptionText)}</div>
            <p class="wrong-note-explanation"><strong>해설</strong>: ${escapeHtml(note.explanation)}</p>
            ${feedback ? `<div class="ai-feedback"><strong>AI 맞춤 해설</strong><p>${escapeHtml(feedback.feedback)}</p>${feedback.recommendedActions?.length ? `<ul>${feedback.recommendedActions.map(item => `<li>${escapeHtml(item)}</li>`).join("")}</ul>` : ""}</div>` : ""}
            <div class="wrong-note-actions">
              <button class="button ghost small" type="button" data-ai-feedback="${note.questionId}">${feedback ? "AI 해설 다시 생성" : "AI 해설 받기"}</button>
              ${note.relearned ? "" : `<button class="button secondary small" type="button" data-relearn-question="${note.questionId}">재학습 완료로 표시</button>`}
            </div>
          </article>`;
        }).join("")
      : '<div class="empty-state"><div><strong>아직 쌓인 오답 노트가 없어요.</strong><span>문제 풀이 후 오답이 자동으로 기록됩니다.</span></div></div>';
  }

  function renderAiFeatures() {
    const panel = $("#aiQuotaCard");
    panel.hidden = !state.authenticated;
    $("#aiTokenChip").hidden = !state.authenticated;
    const quota = state.ai.quota;
    const preference = state.ai.preferences || defaultState.ai.preferences;
    if (quota) {
      const remaining = Number(quota.remainingToday || 0);
      const dailyLimit = Number(quota.dailyLimit || 0);
      const percent = dailyLimit > 0 ? Math.round((remaining / dailyLimit) * 100) : 0;
      const boundedPercent = Math.max(0, Math.min(percent, 100));
      $("#aiRemainingTokens").textContent = remaining.toLocaleString("ko-KR");
      $("#aiDailyLimit").textContent = dailyLimit.toLocaleString("ko-KR");
      $("#aiQuotaBar").style.width = `${boundedPercent}%`;
      $("#aiTokenChipValue").textContent = `${remaining.toLocaleString("ko-KR")} / ${dailyLimit.toLocaleString("ko-KR")}`;
      $("#aiTokenChipBar").style.width = `${boundedPercent}%`;
      $("#aiTokenChip").classList.toggle("low", boundedPercent > 20 && boundedPercent <= 40);
      $("#aiTokenChip").classList.toggle("critical", boundedPercent <= 20);
      $(".ai-token-chip-track").setAttribute("aria-valuenow", String(boundedPercent));
      $(".ai-token-chip-track").setAttribute("aria-valuetext", `${remaining.toLocaleString("ko-KR")} / ${dailyLimit.toLocaleString("ko-KR")} 남음`);
      $("#aiTokenChip").title = `오늘 AI 토큰 ${remaining.toLocaleString("ko-KR")} / ${dailyLimit.toLocaleString("ko-KR")} 남음`;
      $("#aiQuotaStatus").textContent = preference.enabled ? `오늘 ${Number(quota.usedToday).toLocaleString("ko-KR")} 토큰 사용` : "동의 후 AI 기능 사용 가능";
    } else {
      $("#aiRemainingTokens").textContent = "-";
      $("#aiDailyLimit").textContent = "-";
      $("#aiQuotaBar").style.width = "0%";
      $("#aiTokenChipValue").textContent = "- / -";
      $("#aiTokenChipBar").style.width = "0%";
      $("#aiTokenChip").classList.remove("low", "critical");
      $(".ai-token-chip-track").setAttribute("aria-valuenow", "0");
      $(".ai-token-chip-track").setAttribute("aria-valuetext", "확인할 수 없음");
      $("#aiQuotaStatus").textContent = apiConfig.enabled ? "AI 상태 확인 필요" : "데모 모드";
    }
    $("#aiSettingsButton").textContent = preference.enabled ? "AI 설정 변경" : "AI 사용 설정";
    $("#generateRecommendation").disabled = !state.authenticated || !hasCompleteProfile();

    const code = state.activeSubjectCode || state.subjects[0];
    const recommendation = (state.ai.recommendations || []).find(item => item.subjectCode === code);
    $("#aiRecommendationResult").className = recommendation ? "ai-recommendation-result" : "empty-state";
    $("#aiRecommendationResult").innerHTML = recommendation
      ? `<strong>${escapeHtml(recommendation.title)}</strong><p>${escapeHtml(recommendation.content)}</p><div class="ai-recommendation-meta">${escapeHtml(recommendation.subjectName)} · 우선순위 ${recommendation.priority}/5</div>`
      : '<div><strong>아직 생성된 추천이 없습니다.</strong><span>현재 선택한 과목의 추천 생성 버튼을 눌러 주세요.</span></div>';
  }

  function updateUI() {
    const completed = state.tasks.filter(Boolean).length;
    const progress = planProgress();
    const hasProfile = hasCompleteProfile();
    const tasksDone = state.tasks.every(Boolean);

    $("#loginButton").hidden = state.authenticated;
    $("#signupButton").hidden = state.authenticated;
    $("#resetDemo").hidden = apiConfig.enabled;
    $("#userMenu").hidden = !state.authenticated;
    $("#adminMenuItem").hidden = !state.authenticated || !state.isAdmin;
    $("#withdrawButton").hidden = !state.authenticated;
    if (!state.authenticated) closeUserMenu();
    if (state.authenticated) {
      $("#userName").textContent = state.userName;
      $("#userInitial").textContent = state.userName.slice(0, 1);
    }
    updateReauthStatus();

    $("#heroProgressText").textContent = `${progress}%`;
    $("#heroProgressBar").style.width = `${progress}%`;
    $("#heroProgress").setAttribute("aria-valuenow", String(progress));
    $("#completedCount").textContent = String(completed);
    $("#overallProgress").textContent = state.quizFinished ? "100" : String(progress);
    $("#overallCaption").textContent = state.quizFinished ? "오늘 학습이 대시보드에 반영됐어요." : completed ? "오늘 플랜을 진행하고 있어요." : "첫 학습을 시작하면 반영돼요.";

    if (state.quizFinished) {
      const accuracy = Math.round((state.quizCorrect / Math.max(state.quizTotal, 1)) * 100);
      $("#accuracyValue").textContent = String(accuracy);
      $("#accuracyUnit").textContent = "%";
      $("#accuracyCaption").textContent = `${state.quizTotal}문제 중 ${state.quizCorrect}문제 정답`;
    } else {
      $("#accuracyValue").textContent = "-";
      $("#accuracyUnit").textContent = "";
      $("#accuracyCaption").textContent = "문제 풀이 후 계산돼요.";
    }

    $("#profileLevel").textContent = hasProfile ? "과목별 맞춤 수준" : "미설정";
    $("#subjectTags").innerHTML = hasProfile
      ? state.subjects.map(code => `<span class="subject-tag">${escapeHtml(subjectName(code))} · ${escapeHtml(state.subjectLevels[code])}</span>`).join("")
      : '<span class="subject-tag">과목을 선택해 주세요</span>';
    const currentWrongFilter = $("#wrongSubjectFilter").value;
    $("#wrongSubjectFilter").innerHTML = '<option value="">전체 과목</option>' + state.subjects.map(code => `<option value="${escapeHtml(code)}">${escapeHtml(subjectName(code))}</option>`).join("");
    $("#wrongSubjectFilter").value = state.subjects.includes(currentWrongFilter) ? currentWrongFilter : "";
    $("#setupProgressText").textContent = hasProfile ? "100%" : state.authenticated ? "50%" : "0%";
    $("#setupProgressBar").style.width = hasProfile ? "100%" : state.authenticated ? "50%" : "0%";
    $("#generatePlan").disabled = !hasProfile;
    $("#generatePlan").textContent = state.planGenerated ? "AI 플랜 다시 생성하기" : "AI 학습 플랜 생성하기";

    $("#planEmpty").hidden = state.planGenerated;
    $("#planContent").hidden = !state.planGenerated;
    if (state.planGenerated) {
      const focus = state.activeSubjectCode || state.subjects[0] || "KUBERNETES";
      const focusLevel = state.subjectLevels[focus] || "초급";
      $("#focusSubject").textContent = `${subjectName(focus)} · ${focusLevel}`;
      const fallbackTitles = [`${subjectName(focus)} 핵심 개념 익히기`, `${subjectName(focus)} 미니 실습 따라하기`, `${subjectName(focus)} 핵심 내용 복습`];
      const fallbackContents = ["개념 카드와 예제로 준비하기", "단계별 미니 실습 수행하기", "오늘 배운 내용 한 번 더 확인하기"];
      ["One", "Two", "Three"].forEach((suffix, index) => {
        const planStep = state.planSteps.find(item => item.stepNo === index + 1);
        $(`#task${suffix}Title`).textContent = planStep?.title || fallbackTitles[index];
        $(`#task${suffix}Content`).innerHTML = planStepContentHtml(planStep?.content || fallbackContents[index]);
      });
      $("#planBasis").textContent = state.ai.planRationale?.[focus] || `${subjectName(focus)} · ${focusLevel} 설정 기준`;
      $("#planStatus").textContent = state.quizFinished ? "학습 완료" : completed ? "학습 중" : "플랜 준비됨";
      $("#planNote").textContent = state.quizFinished
        ? "오늘의 학습 결과가 대시보드에 반영되었습니다."
        : completed === 3 ? "학습을 모두 마쳤어요. 확인 문제에 도전해 보세요." : `3단계 중 ${completed}단계를 완료했습니다.`;
    } else {
      const focus = state.activeSubjectCode || state.subjects[0];
      $("#focusSubject").textContent = hasProfile ? `${subjectName(focus)} · ${state.subjectLevels[focus]}` : "과목을 먼저 선택해 주세요";
      $("#planStatus").textContent = hasProfile ? "생성 대기" : "설정 전";
      $("#planNote").textContent = hasProfile ? "오늘의 플랜 생성 버튼을 눌러 학습을 시작하세요." : "회원가입 후 학습 프로필을 설정하면 맞춤 플랜을 생성할 수 있어요.";
    }

    $$(".timeline-item").forEach((item, index) => {
      const done = Boolean(state.tasks[index]);
      item.classList.toggle("completed", done);
      const button = $(".check-button", item);
      button.setAttribute("aria-pressed", String(done));
      button.setAttribute("aria-label", `${index + 1}단계 ${done ? "완료 취소" : "완료"}`);
    });
    $("#quizButton").disabled = !hasProfile || !state.quizSubjectCode;
    $("#aiQuizButton").disabled = !hasProfile || !state.quizSubjectCode;
    $("#quizGuide").textContent = state.quizFinished
      ? "최근 문제은행 결과가 대시보드에 반영되었습니다. 기존 문제 또는 AI 새 문제를 다시 풀 수 있어요."
      : "플랜 체크 여부와 관계없이 기존 문제은행 또는 AI가 새로 만든 5문제를 풀 수 있습니다.";

    if (!state.authenticated) $("#mainAction").textContent = "회원가입하고 시작하기";
    else if (!hasProfile) $("#mainAction").textContent = "과목·수준 설정하기";
    else if (!state.planGenerated) $("#mainAction").textContent = "오늘의 플랜 생성하기";
    else if (!tasksDone) $("#mainAction").textContent = "오늘 학습 이어하기";
    else if (!state.quizFinished) $("#mainAction").textContent = "확인 문제 풀기";
    else $("#mainAction").textContent = "오늘 학습 완료";

    renderDashboardDetails();
    renderSubjectTabs();
    renderPlanHistory();
    renderWrongNotes();
    renderAiFeatures();
  }

  function setAuthMode(mode) {
    authMode = mode;
    clearAuthFields();
    $$('[data-auth-mode]').forEach(button => button.classList.toggle("active", button.dataset.authMode === mode));
    $("#nameField").hidden = mode === "login";
    $("#authTitle").textContent = mode === "signup" ? "NeuroPlan 회원가입" : "NeuroPlan 로그인";
    $("#authDescription").textContent = mode === "signup" ? "간단한 정보로 학습을 시작해 보세요." : "가입한 이메일과 비밀번호를 입력해 주세요.";
    $("#authHint").hidden = apiConfig.enabled || mode !== "login";
    $("#authPassword").autocomplete = mode === "signup" ? "new-password" : "current-password";
    $("#authSubmit").textContent = mode === "signup" ? "회원가입 완료" : "로그인";
  }

  function openProfile() {
    if (!state.authenticated) {
      setAuthMode("signup");
      openModal("authModal");
      toast("먼저 회원가입 또는 로그인이 필요해요.");
      return;
    }
    draftSubjects = [...state.subjects];
    draftSubjectLevels = { ...state.subjectLevels };
    renderSubjectChoices();
    renderSubjectLevelSettings();
    $("#profileMessage").textContent = "";
    openModal("profileModal");
  }

  async function generatePlan() {
    if (!hasCompleteProfile()) return;
    await ensureAiEnabled(async () => {
      const button = $("#generatePlan");
      button.disabled = true;
      const code = state.activeSubjectCode || state.subjects[0];
      await showAiLoading(`AI가 ${subjectName(code)} 학습 플랜을 만들고 있어요`);
      try {
        if (apiConfig.enabled) {
          const generated = await apiRequest(`/ai/plans?subjectCode=${encodeURIComponent(code)}`, { method: "POST" });
          const plan = generated.plan;
          state.plans = [...state.plans.filter(item => item.subjectCode !== plan.subjectCode), plan];
          state.ai.planRationale = { ...state.ai.planRationale, [plan.subjectCode]: generated.rationale };
          updateAiQuota(generated.quota);
          selectActivePlan(plan.subjectCode);
          if (generated.fallback) toast("AI 연결을 사용할 수 없어 검증된 기본 플랜을 생성했습니다.");
        } else {
          state.planId = 1;
          state.planGenerated = true;
          state.planSteps = [];
          state.tasks = [false, false, false];
          saveState();
        }
        state.quizFinished = false;
        state.quizCorrect = 0;
        state.quizTotal = 0;
        updateUI();
        toast(`${subjectName(code)} AI 학습 플랜을 만들었어요.`);
        $("#todayPlanTitle").scrollIntoView({ behavior: "smooth", block: "center" });
      } catch (error) {
        await handleAiRequestError(error);
      } finally {
        setAiLoading(false);
        button.disabled = !hasCompleteProfile();
      }
    });
  }

  async function generateRecommendation() {
    if (!hasCompleteProfile()) return;
    await ensureAiEnabled(async () => {
      const button = $("#generateRecommendation");
      const code = state.activeSubjectCode || state.subjects[0];
      button.disabled = true;
      await showAiLoading(`AI가 ${subjectName(code)} 학습 기록을 분석하고 있어요`);
      try {
        const generated = apiConfig.enabled
          ? await apiRequest(`/ai/recommendations?subjectCode=${encodeURIComponent(code)}`, { method: "POST" })
          : { id: 1, subjectCode: code, subjectName: subjectName(code), title: `${subjectName(code)} 핵심 개념 복습`, content: "최근 학습 기록을 기준으로 핵심 개념을 다시 확인하세요.", priority: 3 };
        const recommendation = {
          ...generated,
          subjectCode: generated.subjectCode || code,
          subjectName: generated.subjectName || subjectName(code)
        };
        state.ai.recommendations = [recommendation, ...(state.ai.recommendations || []).filter(item => item.subjectCode !== code)];
        updateAiQuota(generated.quota);
        updateUI();
        toast(generated.fallback ? "기본 재학습 추천을 준비했습니다." : "AI 재학습 추천을 생성했습니다.");
      } catch (error) {
        await handleAiRequestError(error);
      } finally {
        setAiLoading(false);
        button.disabled = !hasCompleteProfile();
      }
    });
  }

  async function startQuiz(mode = "BANK") {
    const code = state.quizSubjectCode || state.subjects[0];
    if (!code) return;
    const useAi = mode === "AI";
    const run = async () => {
      const button = useAi ? $("#aiQuizButton") : $("#quizButton");
      button.disabled = true;
      if (useAi) await showAiLoading(`AI가 ${subjectName(code)} 확인 문제 5개를 만들고 있어요`);
      try {
        if (useAi && apiConfig.enabled) {
          const generated = await apiRequest(`/ai/questions?subjectCode=${encodeURIComponent(code)}&count=5`, { method: "POST" });
          questions = generated.questions.map(question => ({
            ...question,
            id: question.questionNo,
            options: question.options.map(option => ({ id: option.optionNo, text: option.text }))
          }));
          aiQuizRunId = generated.generationRunId;
          updateAiQuota(generated.quota);
          if (generated.fallback) toast("AI 응답 대신 검증된 기본 문제를 준비했습니다.");
        } else {
          questions = apiConfig.enabled
            ? await apiRequest(`/learning/diagnosis/questions?subjectCode=${encodeURIComponent(code)}`)
            : fallbackQuestions;
          aiQuizRunId = useAi ? 1 : null;
        }
        quizMode = useAi ? "AI" : "BANK";
        quizAnswers = [];
        quizIndex = 0;
        quizScore = 0;
        chosenAnswer = null;
        answerChecked = false;
        renderQuestion();
        openModal("quizModal");
      } catch (error) {
        if (useAi) await handleAiRequestError(error);
        else toast(error.message);
      } finally {
        if (useAi) setAiLoading(false);
        button.disabled = !hasCompleteProfile() || !state.quizSubjectCode;
      }
    };
    if (useAi) await ensureAiEnabled(run);
    else await run();
  }

  function renderQuestion() {
    const question = questions[quizIndex];
    $("#quizTitle").textContent = quizMode === "AI" ? "AI 확인 문제" : "오늘의 확인 문제";
    $("#questionCounter").textContent = `${quizIndex + 1} / ${questions.length}`;
    $("#quizProgressBar").style.width = `${((quizIndex + 1) / questions.length) * 100}%`;
    $("#questionSubject").textContent = `${question.subjectName} · ${question.difficulty}`;
    $("#questionText").textContent = question.text;
    $("#quizSubjectText").textContent = quizMode === "AI"
      ? `${subjectName(state.quizSubjectCode || state.subjects[0])} 맞춤 문제 · 결과는 연습용입니다.`
      : `${subjectName(state.quizSubjectCode || state.subjects[0])} 핵심 내용을 확인합니다.`;
    $("#answerList").innerHTML = question.options.map((option, index) => `
      <button class="answer" type="button" data-answer="${option.id}">
        <span class="answer-letter">${String.fromCharCode(65 + index)}</span><span>${escapeHtml(option.text)}</span>
      </button>`).join("");
    $("#explanation").hidden = true;
    $("#explanation").textContent = "";
    $("#nextQuestion").disabled = true;
    $("#nextQuestion").textContent = "정답 확인";
    chosenAnswer = null;
    answerChecked = false;
  }

  document.addEventListener("click", event => {
    const pageButton = event.target.closest("[data-page]");
    if (pageButton) showPage(pageButton.dataset.page);

    const planSubject = event.target.closest("[data-plan-subject]");
    if (planSubject) {
      selectActivePlan(planSubject.dataset.planSubject);
      updateUI();
    }

    const quizSubject = event.target.closest("[data-quiz-subject]");
    if (quizSubject) {
      state.quizSubjectCode = quizSubject.dataset.quizSubject;
      updateUI();
    }

    const close = event.target.closest("[data-close]");
    if (close) closeModal(close.dataset.close);

    const authTab = event.target.closest("[data-auth-mode]");
    if (authTab) setAuthMode(authTab.dataset.authMode);

    const subject = event.target.closest("[data-subject]");
    if (subject) {
      const code = subject.dataset.subject;
      if (draftSubjects.includes(code)) {
        draftSubjects = draftSubjects.filter(item => item !== code);
        delete draftSubjectLevels[code];
      } else {
        if (draftSubjects.length >= 3) {
          $("#profileMessage").textContent = "학습 과목은 최대 3개까지 선택할 수 있습니다.";
          return;
        }
        draftSubjects.push(code);
      }
      $("#profileMessage").textContent = "";
      renderSubjectChoices();
      renderSubjectLevelSettings();
    }

    const level = event.target.closest("[data-level-subject]");
    if (level) {
      draftSubjectLevels[level.dataset.levelSubject] = level.dataset.level;
      renderSubjectLevelSettings();
    }

    const answer = event.target.closest("[data-answer]");
    if (answer && !answerChecked) {
      chosenAnswer = Number(answer.dataset.answer);
      $$('[data-answer]').forEach(button => button.classList.toggle("selected", Number(button.dataset.answer) === chosenAnswer));
      $("#nextQuestion").disabled = false;
    }
  });

  $("#authForm").addEventListener("submit", async event => {
    event.preventDefault();
    const name = $("#authName").value.trim();
    const email = $("#authEmail").value.trim();
    const password = $("#authPassword").value;
    const validNickname = /^[\p{L}\p{N}][\p{L}\p{N} ._-]{1,49}$/u.test(name);
    if ((authMode === "signup" && !validNickname) || !email.includes("@") || password.length < 8) {
      $("#authMessage").textContent = authMode === "signup" && !validNickname
        ? "닉네임은 2~50자의 문자·숫자로 시작하고 공백, 점, 밑줄, 하이픈만 사용할 수 있습니다."
        : "올바른 이메일과 8자 이상의 비밀번호를 확인해 주세요.";
      return;
    }
    $("#authSubmit").disabled = true;
    $("#authMessage").textContent = apiConfig.enabled ? "서버에서 확인하고 있어요..." : "";
    try {
      if (apiConfig.enabled) {
        const payload = await apiRequest(authMode === "signup" ? "/auth/signup" : "/auth/login", {
          method: "POST",
          body: JSON.stringify(authMode === "signup" ? { nickname: name, email, password } : { email, password })
        });
        state.userName = payload.user.nickname;
        state.userEmail = payload.user.email;
      } else {
        state.userName = authMode === "signup" ? name : (state.userName || email.split("@")[0]);
        state.userEmail = email.toLowerCase();
      }
      state.authenticated = true;
      saveSessionHint();
      if (apiConfig.enabled) await loadLearningState();
      else saveState();
      await detectAdmin();
      closeModal("authModal");
      updateUI();
      toast(authMode === "signup" ? `${state.userName}님, 가입을 환영합니다!` : `${state.userName}님, 다시 만나 반가워요.`);
      if (!hasCompleteProfile()) setTimeout(openProfile, 260);
    } catch (error) {
      $("#authMessage").textContent = error.message;
    } finally {
      $("#authSubmit").disabled = false;
    }
  });

  $("#saveProfile").addEventListener("click", async () => {
    const missingLevels = draftSubjects.filter(code => !draftSubjectLevels[code]);
    if (!draftSubjects.length || draftSubjects.length > 3 || missingLevels.length) {
      $("#profileMessage").textContent = !draftSubjects.length
        ? "학습 과목을 최소 한 개 선택해 주세요."
        : draftSubjects.length > 3 ? "학습 과목은 최대 3개까지 선택할 수 있습니다."
          : `${missingLevels.map(subjectName).join(", ")} 과목의 수준을 선택해 주세요.`;
      return;
    }
    const button = $("#saveProfile");
    button.disabled = true;
    try {
      if (apiConfig.enabled) {
        const profile = await apiRequest("/learning/profile", {
          method: "PUT",
          body: JSON.stringify({ subjects: draftSubjects.map(code => ({ code, learningLevel: levelToApi[draftSubjectLevels[code]] })) })
        });
        applyProfile(profile);
      } else {
        state.subjects = [...draftSubjects];
        state.subjectLevels = Object.fromEntries(state.subjects.map(code => [code, draftSubjectLevels[code]]));
      }
      state.plans = [];
      applyPlan(null);
      state.quizFinished = false;
      state.quizCorrect = 0;
      state.quizTotal = 0;
      saveState();
      closeModal("profileModal");
      updateUI();
      toast(`${profileLabel()} 프로필을 저장했어요.`);
    } catch (error) {
      $("#profileMessage").textContent = error.message;
    } finally {
      button.disabled = false;
    }
  });

  $("#generatePlan").addEventListener("click", generatePlan);
  $("#generateRecommendation").addEventListener("click", generateRecommendation);
  $("#aiSettingsButton").addEventListener("click", () => openAiSettings());
  $("#aiConsentForm").addEventListener("submit", async event => {
    event.preventDefault();
    const consent = $("#aiConsentCheck").checked;
    const availableMinutes = Number($("#aiAvailableMinutes").value);
    if (!consent) {
      $("#aiConsentMessage").textContent = "외부 AI 처리 안내 동의가 필요합니다.";
      return;
    }
    if (!Number.isInteger(availableMinutes) || availableMinutes < 5 || availableMinutes > 240) {
      $("#aiConsentMessage").textContent = "희망 학습 시간은 5분에서 240분 사이로 입력해 주세요.";
      return;
    }
    const submit = $("#aiConsentSubmit");
    submit.disabled = true;
    try {
      const preference = apiConfig.enabled
        ? await apiRequest("/ai/preferences", {
            method: "PUT",
            body: JSON.stringify({
              enabled: true,
              consent: true,
              explanationStyle: $("#aiExplanationStyle").value,
              availableMinutes
            })
          })
        : {
            enabled: true,
            consentAt: new Date().toISOString(),
            explanationStyle: $("#aiExplanationStyle").value,
            availableMinutes
          };
      state.ai.preferences = preference;
      const action = pendingAiAction;
      pendingAiAction = null;
      closeModal("aiConsentModal");
      updateUI();
      toast("AI 학습 기능을 사용할 수 있습니다.");
      if (action) await action();
    } catch (error) {
      $("#aiConsentMessage").textContent = error.message;
    } finally {
      submit.disabled = false;
    }
  });
  $("#quizButton").addEventListener("click", () => startQuiz("BANK"));
  $("#aiQuizButton").addEventListener("click", () => startQuiz("AI"));
  $("#editProfile").addEventListener("click", openProfile);
  $("#profileAction").addEventListener("click", openProfile);
  $("#loginButton").addEventListener("click", () => { setAuthMode("login"); openModal("authModal"); });
  $("#signupButton").addEventListener("click", () => { setAuthMode("signup"); openModal("authModal"); });
  $("#userMenuButton").addEventListener("click", () => {
    if ($("#userDropdown").hidden) openUserMenu();
    else closeUserMenu({ restoreFocus: true });
  });
  $("#userMenuScrim").addEventListener("click", () => closeUserMenu({ restoreFocus: true }));
  $("#userMenuButton").addEventListener("keydown", event => {
    if (!['ArrowDown', 'Enter', ' '].includes(event.key)) return;
    event.preventDefault();
    openUserMenu();
    $(".user-menu-item:not([hidden])", $("#userDropdown"))?.focus();
  });
  $("#userDropdown").addEventListener("keydown", event => {
    const items = $$(".user-menu-item:not([hidden])", $("#userDropdown"));
    const currentIndex = items.indexOf(document.activeElement);
    if (event.key === "Escape") {
      event.preventDefault();
      closeUserMenu({ restoreFocus: true });
      return;
    }
    if (!['ArrowDown', 'ArrowUp'].includes(event.key) || !items.length) return;
    event.preventDefault();
    const delta = event.key === "ArrowDown" ? 1 : -1;
    items[(currentIndex + delta + items.length) % items.length].focus();
  });
  $("#userDropdown").addEventListener("click", async event => {
    const item = event.target.closest("[data-user-action]");
    if (!item) return;
    const action = item.dataset.userAction;
    closeUserMenu();
    try {
      if (action === "account") await openAccountSettings();
      if (action === "admin") await openAdminPage();
      if (action === "logout") await logout();
      if (action === "sessions") {
        if (!confirm("현재 기기를 포함한 모든 로그인 세션을 종료할까요?")) return;
        await requireReauth(async () => {
          if (apiConfig.enabled) await apiRequest("/auth/sessions", { method: "DELETE" });
          resetAuthenticatedState();
          showLearningPage();
          updateUI();
          toast("모든 로그인 세션을 종료했습니다.");
        });
      }
    } catch (error) {
      toast(error.message);
    }
  });
  $("#adminBackButton").addEventListener("click", showLearningPage);
  $("#adminRefresh").addEventListener("click", async () => {
    try {
      await loadAdminOverview();
      toast("관리자 데이터를 새로고침했습니다.");
    } catch (error) {
      toast(error.message);
    }
  });
  $("#adminUserSearchForm").addEventListener("submit", async event => {
    event.preventDefault();
    adminPageIndex = 0;
    try { await loadAdminOverview(); } catch (error) { toast(error.message); }
  });
  $("#adminPrevPage").addEventListener("click", async () => {
    if (adminPageIndex <= 0) return;
    adminPageIndex -= 1;
    try { await loadAdminOverview(); } catch (error) { toast(error.message); }
  });
  $("#adminNextPage").addEventListener("click", async () => {
    const totalPages = Math.max(1, Math.ceil((adminUserPage.totalElements || 0) / adminPageSize));
    if (adminPageIndex + 1 >= totalPages) return;
    adminPageIndex += 1;
    try { await loadAdminOverview(); } catch (error) { toast(error.message); }
  });
  $("#adminSubjectForm").addEventListener("submit", async event => {
    event.preventDefault();
    if (!apiConfig.enabled) return toast("API 연동 모드에서 사용할 수 있습니다.");
    try {
      await apiRequest("/admin/subjects", {
        method: "POST",
        body: JSON.stringify({ code: $("#adminSubjectCode").value, name: $("#adminSubjectName").value, active: true })
      });
      event.target.reset();
      await loadAdminOverview();
      toast("과목을 등록했습니다.");
    } catch (error) { toast(error.message); }
  });
  $("#adminQuestionForm").addEventListener("submit", async event => {
    event.preventDefault();
    if (!apiConfig.enabled) return toast("API 연동 모드에서 사용할 수 있습니다.");
    const options = $("#adminQuestionOptions").value.split(/\r?\n/).map(value => value.trim()).filter(Boolean)
      .map(value => ({ text: value.replace(/^\*/, "").trim(), correct: value.startsWith("*") }));
    if (options.length < 2 || options.filter(item => item.correct).length !== 1) {
      return toast("보기는 2개 이상이고, 정답 표시는 정확히 하나여야 합니다.");
    }
    try {
      const questionId = editingAdminQuestionId;
      await apiRequest(questionId ? `/admin/questions/${questionId}` : "/admin/questions", {
        method: questionId ? "PUT" : "POST",
        body: JSON.stringify({
          subjectId: Number($("#adminQuestionSubject").value),
          questionNo: Number($("#adminQuestionNo").value),
          difficulty: "BEGINNER",
          questionText: $("#adminQuestionText").value,
          explanation: $("#adminQuestionExplanation").value,
          active: questionId ? event.target.dataset.editingActive === "true" : true,
          options
        })
      });
      resetAdminQuestionForm();
      await loadAdminOverview();
      toast(questionId ? "진단 문제를 수정했습니다." : "진단 문제를 등록했습니다.");
    } catch (error) { toast(error.message); }
  });
  $("#adminQuestionCancel").addEventListener("click", resetAdminQuestionForm);
  $("#wrongSubjectFilter").addEventListener("change", renderWrongNotes);
  $("#wrongStatusFilter").addEventListener("change", renderWrongNotes);
  $("#refreshHistory").addEventListener("click", loadPlanHistory);
  $("#reauthForm").addEventListener("submit", async event => {
    event.preventDefault();
    const password = $("#reauthPassword").value;
    const submit = $("#reauthSubmit");
    submit.disabled = true;
    $("#reauthMessage").textContent = "";
    try {
      const response = apiConfig.enabled
        ? await apiRequest("/auth/reauth", {
            method: "POST",
            body: JSON.stringify({ password })
          })
        : { expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString() };
      const expiresAt = Date.parse(response.expiresAt);
      reauthExpiresAt = Number.isNaN(expiresAt) ? Date.now() + 5 * 60 * 1000 : expiresAt;
      const action = pendingSecureAction;
      pendingSecureAction = null;
      updateReauthStatus();
      if (action) {
        $("#reauthMessage").textContent = "계정 정보를 불러오고 있어요...";
        try {
          await action();
        } catch (actionError) {
          toast(actionError.message);
        }
      }
      closeModal("reauthModal");
    } catch (error) {
      $("#reauthMessage").textContent = error.message;
    } finally {
      submit.disabled = false;
    }
  });
  $("#accountNickname").addEventListener("input", () => {
    accountDirty = Boolean(accountDetails)
      && $("#accountNickname").value.trim() !== accountDetails.user.nickname;
  });
  $("#newPassword").addEventListener("input", () => { accountDirty = Boolean($("#newPassword").value); });
  $("#confirmNewPassword").addEventListener("input", () => { accountDirty = Boolean($("#confirmNewPassword").value || $("#newPassword").value); });
  $("#nicknameForm").addEventListener("submit", async event => {
    event.preventDefault();
    const nickname = $("#accountNickname").value.trim();
    $("#nicknameMessage").textContent = "";
    if (!/^[\p{L}\p{N}][\p{L}\p{N} ._-]{1,49}$/u.test(nickname)) {
      $("#nicknameMessage").textContent = "닉네임은 2~50자의 문자·숫자로 시작하고 공백, 점, 밑줄, 하이픈만 사용할 수 있습니다.";
      return;
    }
    const submit = $("button[type='submit']", event.currentTarget);
    submit.disabled = true;
    try {
      await requireReauth(async () => {
        const payload = apiConfig.enabled
          ? await apiRequest("/auth/profile", {
              method: "PATCH",
              body: JSON.stringify({ nickname })
            })
          : { user: { ...accountDetails.user, nickname, updatedAt: new Date().toISOString() } };
        state.userName = payload.user.nickname;
        accountDetails = { ...accountDetails, user: payload.user };
        accountDirty = false;
        updateUI();
        await loadAccountDetails();
        toast("닉네임을 변경했습니다.");
      });
    } catch (error) {
      $("#nicknameMessage").textContent = error.message;
    } finally {
      submit.disabled = false;
    }
  });
  $("#changePasswordForm").addEventListener("submit", async event => {
    event.preventDefault();
    const newPassword = $("#newPassword").value;
    $("#passwordMessage").textContent = "";
    if (newPassword.length < 8 || newPassword.length > 72) {
      $("#passwordMessage").textContent = "새 비밀번호는 8~72자로 입력해 주세요.";
      return;
    }
    if (newPassword !== $("#confirmNewPassword").value) {
      $("#passwordMessage").textContent = "새 비밀번호 확인이 일치하지 않습니다.";
      return;
    }
    const submit = $("button[type='submit']", event.currentTarget);
    submit.disabled = true;
    try {
      await requireReauth(async () => {
        if (apiConfig.enabled) {
          await apiRequest("/auth/password", { method: "PUT", body: JSON.stringify({ newPassword }) });
        }
        event.target.reset();
        resetAuthenticatedState();
        showLearningPage();
        updateUI();
        toast("비밀번호를 변경했습니다. 새 비밀번호로 다시 로그인해 주세요.");
      });
    } catch (error) {
      $("#passwordMessage").textContent = error.message;
    } finally {
      submit.disabled = false;
    }
  });
  $("#revokeSessionsButton").addEventListener("click", async () => {
    if (!confirm("모든 기기의 로그인 세션을 종료할까요?")) return;
    try {
      await requireReauth(async () => {
        if (apiConfig.enabled) await apiRequest("/auth/sessions", { method: "DELETE" });
        resetAuthenticatedState();
        showLearningPage();
        updateUI();
        toast("모든 로그인 세션을 종료했습니다.");
      });
    } catch (error) { toast(error.message); }
  });
  $("#homeBrand").addEventListener("click", event => {
    event.preventDefault();
    showLearningPage();
  });
  $("#withdrawButton").addEventListener("click", async () => {
    try {
      await requireReauth(async () => {
        $("#withdrawForm").reset();
        $("#withdrawMessage").textContent = "";
        openModal("withdrawModal");
      });
    } catch (error) {
      toast(error.message);
    }
  });

  $("#withdrawForm").addEventListener("submit", async event => {
    event.preventDefault();
    const password = $("#withdrawPassword").value;
    if (password.length < 8) {
      $("#withdrawMessage").textContent = "현재 비밀번호를 8자 이상 입력해 주세요.";
      return;
    }
    if (!confirm("정말 회원 탈퇴하시겠습니까? 이 계정은 다시 로그인할 수 없습니다.")) return;
    $("#withdrawSubmit").disabled = true;
    try {
      await requireReauth(async () => {
        if (apiConfig.enabled) {
          await apiRequest("/auth/withdraw", { method: "POST", body: JSON.stringify({ password }) });
        }
        closeModal("withdrawModal");
        resetAuthenticatedState();
        showLearningPage();
        updateUI();
        toast("회원 탈퇴가 완료되었습니다.");
      });
    } catch (error) {
      $("#withdrawMessage").textContent = error.message;
    } finally {
      $("#withdrawSubmit").disabled = false;
    }
  });

  $("#adminDeleteForm").addEventListener("submit", async event => {
    event.preventDefault();
    if (!adminDeleteTarget) {
      $("#adminDeleteMessage").textContent = "삭제할 사용자를 다시 선택해 주세요.";
      return;
    }
    const confirmEmail = $("#adminDeleteConfirmEmail").value.trim();
    if (confirmEmail.toLowerCase() !== adminDeleteTarget.email.toLowerCase()) {
      $("#adminDeleteMessage").textContent = "입력한 이메일이 삭제 대상과 일치하지 않습니다.";
      return;
    }
    if (!confirm(`${adminDeleteTarget.email} 사용자와 모든 학습 데이터를 영구 삭제할까요?`)) return;
    const target = { ...adminDeleteTarget };
    const submit = $("#adminDeleteSubmit");
    submit.disabled = true;
    $("#adminDeleteMessage").textContent = "";
    try {
      await requireReauth(async () => {
        const result = apiConfig.enabled
          ? await apiRequest(`/admin/users/${target.id}`, {
              method: "DELETE",
              body: JSON.stringify({ confirmEmail })
            })
          : { email: target.email };
        closeModal("adminDeleteModal");
        await loadAdminOverview();
        toast(`${result.email} 사용자를 영구 삭제했습니다.`);
      });
    } catch (error) {
      $("#adminDeleteMessage").textContent = error.message;
    } finally {
      submit.disabled = false;
    }
  });

  $("#mainAction").addEventListener("click", () => {
    const step = currentStep();
    if (step === 1) { setAuthMode("signup"); openModal("authModal"); }
    else if (step === 2) openProfile();
    else if (step === 3) generatePlan();
    else if (step === 4) showPage("plan");
    else if (step === 5) showPage("quiz");
    else toast("오늘 학습을 모두 완료했어요. 수고하셨습니다!");
  });

  $$(".timeline-item").forEach((item, index) => {
    $(".check-button", item).addEventListener("click", async () => {
      const completed = !state.tasks[index];
      const button = $(".check-button", item);
      button.disabled = true;
      try {
        if (apiConfig.enabled) {
          const plan = await apiRequest(`/learning/plans/${state.planId}/steps/${index + 1}`, {
            method: "PATCH", body: JSON.stringify({ completed })
          });
          state.plans = [...state.plans.filter(item => item.subjectCode !== plan.subjectCode), plan];
          selectActivePlan(plan.subjectCode);
          state.stats.completedStepCount = state.tasks.filter(Boolean).length;
        } else {
          state.tasks[index] = completed;
          saveState();
        }
        updateUI();
        toast(completed ? `${index + 1}단계 학습을 완료했어요.` : `${index + 1}단계 완료를 취소했어요.`);
      } catch (error) {
        toast(error.message);
      } finally {
        button.disabled = false;
      }
    });
  });

  $("#nextQuestion").addEventListener("click", async () => {
    const question = questions[quizIndex];
    if (!answerChecked) {
      $("#nextQuestion").disabled = true;
      try {
        const checked = apiConfig.enabled
          ? await apiRequest(quizMode === "AI" ? `/ai/questions/${aiQuizRunId}/check` : "/learning/diagnosis/check", {
              method: "POST",
              body: JSON.stringify(quizMode === "AI"
                ? { questionNo: question.questionNo, selectedOptionNo: chosenAnswer }
                : { questionId: question.id, selectedOptionId: chosenAnswer })
            })
          : {
              correct: chosenAnswer === question.correctOptionId,
              correctOptionId: question.correctOptionId,
              correctOptionNo: question.correctOptionId,
              explanation: question.explanation
            };
        answerChecked = true;
        if (checked.correct) quizScore += 1;
        quizAnswers.push({ questionId: question.id, selectedOptionId: chosenAnswer });
        $$('[data-answer]').forEach(button => {
          const optionId = Number(button.dataset.answer);
          button.classList.remove("selected");
          if (optionId === (quizMode === "AI" ? checked.correctOptionNo : checked.correctOptionId)) button.classList.add("correct");
          else if (optionId === chosenAnswer) button.classList.add("wrong");
        });
        $("#explanation").textContent = `${checked.correct ? "정답입니다. " : "아쉽지만 오답입니다. "}${checked.explanation}`;
        $("#explanation").hidden = false;
        $("#nextQuestion").textContent = quizIndex === questions.length - 1 ? "결과 확인" : "다음 문제";
      } catch (error) {
        toast(error.message);
      } finally {
        $("#nextQuestion").disabled = false;
      }
      return;
    }

    if (quizIndex < questions.length - 1) {
      quizIndex += 1;
      renderQuestion();
      return;
    }

    $("#nextQuestion").disabled = true;
    try {
      if (quizMode === "AI") {
        state.quizCorrect = quizScore;
        state.quizTotal = questions.length;
      } else if (apiConfig.enabled) {
        const result = await apiRequest("/learning/diagnosis/attempts", {
          method: "POST", body: JSON.stringify({ subjectCode: state.quizSubjectCode || state.subjects[0], answers: quizAnswers })
        });
        state.quizCorrect = result.correctAnswers;
        state.quizTotal = result.totalQuestions;
        await loadLearningState();
      } else {
        state.quizCorrect = quizScore;
        state.quizTotal = questions.length;
        state.quizFinished = true;
        state.stats.solvedCount += questions.length;
        state.stats.correctCount += quizScore;
        saveState();
      }
      closeModal("quizModal");
      updateUI();
      const rate = Math.round((state.quizCorrect / Math.max(state.quizTotal, 1)) * 100);
      toast(quizMode === "AI"
        ? `AI 연습 문제 정답률은 ${rate}%입니다. 문제은행 통계에는 반영되지 않습니다.`
        : `정답률 ${rate}%가 대시보드에 반영됐어요.`);
      $("#dashboard").scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      toast(error.message);
    } finally {
      $("#nextQuestion").disabled = false;
    }
  });

  $("#resetDemo").addEventListener("click", () => {
    if (!confirm("회원가입부터 다시 테스트할 수 있도록 데모 데이터를 초기화할까요?")) return;
    state = { ...defaultState, tasks: [false, false, false] };
    localStorage.removeItem(storageKey);
    clearAuthFields();
    updateUI();
    toast("데모 데이터를 초기화했습니다.");
  });

  // 인증/프로필/퀴즈 모달은 실수로 닫히지 않도록 배경 클릭을 무시합니다.
  // 명시적인 X 버튼과 Escape 키만 닫기 동작으로 사용합니다.
  document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    const openBackdrop = $$(".modal-backdrop").find(backdrop => !backdrop.hidden);
    if (openBackdrop) closeModal(openBackdrop.id);
  });

  document.addEventListener("click", async event => {
    const deleteAction = event.target.closest("[data-admin-delete]");
    if (deleteAction && state.isAdmin) {
      const target = adminOverview?.recentUsers.find(item => item.id === Number(deleteAction.dataset.adminDelete));
      if (!target) {
        toast("삭제 대상을 다시 조회해 주세요.");
        return;
      }
      adminDeleteTarget = target;
      $("#adminDeleteNickname").textContent = target.nickname;
      $("#adminDeleteEmail").textContent = target.email;
      $("#adminDeleteForm").reset();
      $("#adminDeleteMessage").textContent = "";
      try {
        await requireReauth(async () => openModal("adminDeleteModal"));
      } catch (error) {
        toast(error.message);
      }
      return;
    }
    const subjectAction = event.target.closest("[data-admin-subject-active]");
    if (subjectAction && state.isAdmin && apiConfig.enabled) {
      const subject = adminSubjects.find(item => item.id === Number(subjectAction.dataset.adminSubjectActive));
      if (!subject) return;
      subjectAction.disabled = true;
      try {
        await apiRequest(`/admin/subjects/${subject.id}`, { method: "PUT", body: JSON.stringify({ code: subject.code, name: subject.name, active: subjectAction.dataset.nextActive === "true" }) });
        await loadAdminOverview();
        toast("과목 사용 상태를 변경했습니다.");
      } catch (error) { toast(error.message); }
      finally { subjectAction.disabled = false; }
      return;
    }
    const questionEditAction = event.target.closest("[data-admin-question-edit]");
    if (questionEditAction && state.isAdmin && apiConfig.enabled) {
      questionEditAction.disabled = true;
      try {
        await editAdminQuestion(Number(questionEditAction.dataset.adminQuestionEdit));
      } catch (error) { toast(error.message); }
      finally { questionEditAction.disabled = false; }
      return;
    }
    const questionAction = event.target.closest("[data-admin-question-active]");
    if (questionAction && state.isAdmin && apiConfig.enabled) {
      questionAction.disabled = true;
      try {
        await apiRequest(`/admin/questions/${Number(questionAction.dataset.adminQuestionActive)}/active`, { method: "PATCH", body: JSON.stringify({ active: questionAction.dataset.nextActive === "true" }) });
        await loadAdminOverview();
        toast("문제 출제 상태를 변경했습니다.");
      } catch (error) { toast(error.message); }
      finally { questionAction.disabled = false; }
      return;
    }
    const learningAction = event.target.closest("[data-user-learning]");
    if (learningAction && state.isAdmin && apiConfig.enabled) {
      learningAction.disabled = true;
      try {
        const detail = await apiRequest(`/admin/users/${Number(learningAction.dataset.userLearning)}/learning`);
        const rate = detail.solvedCount ? Math.round((detail.correctCount / detail.solvedCount) * 100) : 0;
        alert(`${detail.nickname} (${detail.email})\n과목: ${detail.subjects.join(", ") || "미설정"}\n완료 플랜: ${detail.completedPlanCount}/${detail.planCount}\n풀이: ${detail.solvedCount}개 · 정답률: ${rate}%\n미해결 오답: ${detail.unresolvedWrongNotes}개`);
      } catch (error) { toast(error.message); }
      finally { learningAction.disabled = false; }
      return;
    }
    const action = event.target.closest("[data-admin-status]");
    if (!action || !state.isAdmin || !apiConfig.enabled) return;
    const status = action.dataset.adminStatus;
    const userId = Number(action.dataset.userId);
    if (!confirm(`회원 #${userId} 상태를 ${status}(으)로 변경할까요?`)) return;
    action.disabled = true;
    try {
      await apiRequest(`/admin/users/${userId}/status`, {
        method: "PATCH", body: JSON.stringify({ status })
      });
      await loadAdminOverview();
      toast("회원 상태를 변경했습니다.");
    } catch (error) {
      toast(error.message);
    } finally {
      action.disabled = false;
    }
  });

  document.addEventListener("click", async event => {
    const aiFeedbackAction = event.target.closest("[data-ai-feedback]");
    if (aiFeedbackAction && state.authenticated) {
      const questionId = Number(aiFeedbackAction.dataset.aiFeedback);
      await ensureAiEnabled(async () => {
        aiFeedbackAction.disabled = true;
        await showAiLoading("AI가 오답을 분석하고 맞춤 해설을 만들고 있어요");
        try {
          const generated = apiConfig.enabled
            ? await apiRequest(`/ai/wrong-notes/${questionId}/feedback`, { method: "POST" })
            : {
                id: 1,
                generationRunId: 1,
                feedback: "선택한 답과 정답의 핵심 차이를 다시 확인해 보세요.",
                recommendedActions: ["핵심 용어를 한 문장으로 정리하기", "같은 문제를 다시 풀기"]
              };
          state.ai.feedbackByQuestion = {
            ...state.ai.feedbackByQuestion,
            [String(questionId)]: { ...generated, questionId }
          };
          updateAiQuota(generated.quota);
          updateUI();
          toast(generated.fallback ? "기본 오답 해설을 준비했습니다." : "AI 맞춤 오답 해설을 생성했습니다.");
        } catch (error) {
          await handleAiRequestError(error);
        } finally {
          setAiLoading(false);
          aiFeedbackAction.disabled = false;
        }
      });
      return;
    }
    const action = event.target.closest("[data-relearn-question]");
    if (!action || !state.authenticated) return;
    const questionId = Number(action.dataset.relearnQuestion);
    if (!confirm("이 문제를 재학습 완료로 표시할까요?")) return;
    action.disabled = true;
    try {
      if (apiConfig.enabled) {
        await apiRequest(`/learning/wrong-notes/${questionId}/relearn`, { method: "PATCH" });
        await loadLearningState();
      } else {
        state.wrongNotes = state.wrongNotes.map(note => note.questionId === questionId
          ? { ...note, relearned: true, relearnedAt: new Date().toISOString() }
          : note);
        state.dashboard.unresolvedWrongNotes = state.wrongNotes.filter(note => !note.relearned).length;
        saveState();
      }
      updateUI();
      toast("재학습 완료 상태를 기록했습니다.");
    } catch (error) {
      toast(error.message);
    } finally {
      action.disabled = false;
    }
  });

  window.addEventListener("beforeunload", event => {
    if (!accountDirty) return;
    event.preventDefault();
    event.returnValue = "";
  });

  renderSubjectChoices();
  applySessionHint();
  updateUI();
  restoreSession();
})();

