/**
 * React에서 API 서비스를 사용하는 예제
 * 
 * 이 파일은 참고용 예제입니다.
 * 실제 프로젝트에서는 필요에 따라 수정하여 사용하세요.
 */

import React, { useState, useEffect } from 'react';
import { authApi } from './services/mobile-api-service'; // 경로는 프로젝트 구조에 맞게 수정

/**
 * 로그인 컴포넌트 예제
 */
export const LoginComponent = () => {
  const [id, setId] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleLogin = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      const result = await authApi.login(id, password, rememberMe);
      console.log('Login success:', result);
      // 로그인 성공 후 처리 (예: 페이지 이동)
      // navigate('/home');
    } catch (err) {
      setError(err.message || '로그인에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleLogin}>
      <input
        type="text"
        value={id}
        onChange={(e) => setId(e.target.value)}
        placeholder="아이디"
        required
      />
      <input
        type="password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        placeholder="비밀번호"
        required
      />
      <label>
        <input
          type="checkbox"
          checked={rememberMe}
          onChange={(e) => setRememberMe(e.target.checked)}
        />
        자동로그인
      </label>
      {error && <div style={{ color: 'red' }}>{error}</div>}
      <button type="submit" disabled={loading}>
        {loading ? '로그인 중...' : '로그인'}
      </button>
    </form>
  );
};

/**
 * 앱 초기화 및 자동로그인 처리 예제
 */
export const AppInitializer = ({ children }) => {
  const [isInitialized, setIsInitialized] = useState(false);
  const [isAuthenticated, setIsAuthenticated] = useState(false);

  useEffect(() => {
    const initializeApp = async () => {
      try {
        // 1. 먼저 세션 확인
        try {
          const sessionData = await authApi.getSession();
          if (sessionData && sessionData.success) {
            setIsAuthenticated(true);
            setIsInitialized(true);
            return;
          }
        } catch (sessionError) {
          console.log('Session check failed, trying auto login...');
        }

        // 2. 세션이 없으면 자동로그인 시도
        try {
          const autoLoginData = await authApi.autoLogin();
          if (autoLoginData && autoLoginData.success) {
            setIsAuthenticated(true);
          }
        } catch (autoLoginError) {
          console.log('Auto login failed:', autoLoginError);
          // 자동로그인도 실패하면 로그인 페이지로
          setIsAuthenticated(false);
        }
      } catch (error) {
        console.error('App initialization error:', error);
        setIsAuthenticated(false);
      } finally {
        setIsInitialized(true);
      }
    };

    initializeApp();
  }, []);

  if (!isInitialized) {
    return <div>초기화 중...</div>;
  }

  // 인증 상태에 따라 다른 컴포넌트 렌더링
  if (!isAuthenticated) {
    return <LoginComponent />;
  }

  return <>{children}</>;
};

/**
 * 사용자 정보 표시 컴포넌트 예제
 */
export const UserInfoComponent = () => {
  const [userInfo, setUserInfo] = useState(null);

  useEffect(() => {
    // 로컬 스토리지에서 사용자 정보 가져오기
    const info = authApi.getUserInfo();
    setUserInfo(info);

    // 또는 서버에서 최신 정보 가져오기
    authApi.getSession()
      .then(data => {
        if (data && data.success) {
          setUserInfo({
            userId: data.userId,
            userName: data.userName,
            authority: data.authority,
            company: data.company,
            deptCode: data.deptCode,
            deptName: data.deptName
          });
        }
      })
      .catch(err => {
        console.error('Failed to get session:', err);
      });
  }, []);

  const handleLogout = async () => {
    try {
      await authApi.logout();
      setUserInfo(null);
      // 로그아웃 후 처리 (예: 로그인 페이지로 이동)
      // navigate('/login');
    } catch (error) {
      console.error('Logout error:', error);
    }
  };

  if (!userInfo) {
    return <div>사용자 정보를 불러올 수 없습니다.</div>;
  }

  return (
    <div>
      <h2>사용자 정보</h2>
      <p>이름: {userInfo.userName}</p>
      <p>아이디: {userInfo.userId}</p>
      <p>회사: {userInfo.company}</p>
      <p>부서: {userInfo.deptName}</p>
      <button onClick={handleLogout}>로그아웃</button>
    </div>
  );
};

/**
 * Context API를 사용한 인증 상태 관리 예제
 */
import { createContext, useContext, useState, useEffect } from 'react';

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const initAuth = async () => {
      try {
        // 세션 확인
        const sessionData = await authApi.getSession();
        if (sessionData && sessionData.success) {
          setUser({
            userId: sessionData.userId,
            userName: sessionData.userName,
            authority: sessionData.authority,
            company: sessionData.company,
            deptCode: sessionData.deptCode,
            deptName: sessionData.deptName
          });
        }
      } catch (error) {
        // 세션 실패 시 자동로그인 시도
        try {
          const autoLoginData = await authApi.autoLogin();
          if (autoLoginData && autoLoginData.success) {
            setUser({
              userId: autoLoginData.userId,
              userName: autoLoginData.userName,
              authority: autoLoginData.authority,
              company: autoLoginData.company,
              deptCode: autoLoginData.deptCode,
              deptName: autoLoginData.deptName
            });
          }
        } catch (autoLoginError) {
          console.log('Auto login failed');
        }
      } finally {
        setLoading(false);
      }
    };

    initAuth();
  }, []);

  const login = async (id, password, rememberMe) => {
    try {
      const result = await authApi.login(id, password, rememberMe);
      if (result && result.success) {
        setUser({
          userId: result.userId,
          userName: result.userName,
          authority: result.authority,
          company: result.company,
          deptCode: result.deptCode,
          deptName: result.deptName
        });
        return result;
      }
    } catch (error) {
      throw error;
    }
  };

  const logout = async () => {
    try {
      await authApi.logout();
      setUser(null);
    } catch (error) {
      console.error('Logout error:', error);
      // 에러가 발생해도 사용자 상태는 초기화
      setUser(null);
    }
  };

  return (
    <AuthContext.Provider value={{ user, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};

/**
 * 사용 예제:
 * 
 * // App.js
 * import { AuthProvider } from './components/AuthProvider';
 * 
 * function App() {
 *   return (
 *     <AuthProvider>
 *       <YourApp />
 *     </AuthProvider>
 *   );
 * }
 * 
 * // YourComponent.js
 * import { useAuth } from './components/AuthProvider';
 * 
 * function YourComponent() {
 *   const { user, login, logout } = useAuth();
 *   
 *   if (!user) {
 *     return <LoginForm onLogin={login} />;
 *   }
 *   
 *   return (
 *     <div>
 *       <p>안녕하세요, {user.userName}님</p>
 *       <button onClick={logout}>로그아웃</button>
 *     </div>
 *   );
 * }
 */

