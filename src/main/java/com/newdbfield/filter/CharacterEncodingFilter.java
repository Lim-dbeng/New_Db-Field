package com.newdbfield.filter;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public class CharacterEncodingFilter implements Filter {
	private String encoding = "UTF-8";

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		String encodingParam = filterConfig.getInitParameter("encoding");
		if (encodingParam != null && !encodingParam.isEmpty()) {
			this.encoding = encodingParam;
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		// 요청 인코딩 설정
		if (request.getCharacterEncoding() == null) {
			request.setCharacterEncoding(encoding);
		}
		
		// 응답 인코딩 설정
		response.setCharacterEncoding(encoding);
		
		chain.doFilter(request, response);
	}

	@Override
	public void destroy() {
		// cleanup if needed
	}
}

