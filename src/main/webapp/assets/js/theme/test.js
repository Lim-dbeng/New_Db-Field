/**
 * 
 */
 
  
  let mini1 = document.getElementById("mini-1");
  let mini3 = document.getElementById("mini-3");
  let mini4 = document.getElementById("mini-4");
  let mini5 = document.getElementById("mini-5");
  let mini6 = document.getElementById("mini-6");
  
  let right1 = document.getElementById("menu-right-mini-1");
  let right3 = document.getElementById("menu-right-mini-3");
  let right4 = document.getElementById("menu-right-mini-4");
  let right5 = document.getElementById("menu-right-mini-5");
  let right6 = document.getElementById("menu-right-mini-6");
  
  let side = document.getElementById("side");
  let brand = document.getElementById("brand");
  let main = document.getElementById("main-wrapper");
  
  console.log(document.body.dataset.sidebartype);
  
 
  mini1.addEventListener("click", ()=> {
	  mini3.classList.remove("selected");
	  mini4.classList.remove("selected");
	  mini5.classList.remove("selected");
	  mini6.classList.remove("selected");
	  
	  right3.classList.remove("simplebar-scrollable-y");
	  right3.classList.remove("d-block");
	  right4.classList.remove("simplebar-scrollable-y");
	  right4.classList.remove("d-block");
	  right5.classList.remove("simplebar-scrollable-y");
	  right5.classList.remove("d-block");
	  right6.classList.remove("simplebar-scrollable-y");
	  right6.classList.remove("d-block");
	  
	  if(mini1.classList.contains("selected")) {
		  mini1.classList.remove("selected");
		  right1.classList.remove("d-block");
		  side.classList.add("close");
		  main.classList.remove("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'mini-sidebar');
	  } else {
		  mini1.classList.add("selected");
		  right1.classList.add("d-block");
		  side.classList.remove("close");
		  main.classList.add("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'full');
	  }
  });
  
  
  mini3.addEventListener("click", ()=> {
	  mini1.classList.remove("selected");
	  mini4.classList.remove("selected");
	  mini5.classList.remove("selected");
	  mini6.classList.remove("selected");
	  
	  right1.classList.remove("d-block");
	  right4.classList.remove("simplebar-scrollable-y");
	  right4.classList.remove("d-block");
	  right5.classList.remove("simplebar-scrollable-y");
	  right5.classList.remove("d-block");
	  right6.classList.remove("simplebar-scrollable-y");
	  right6.classList.remove("d-block");
	  
	  if(mini3.classList.contains("selected")) {
		  mini3.classList.remove("selected");
		  right3.classList.remove("simplebar-scrollable-y");
		  right3.classList.remove("d-block");
		  side.classList.add("close");
		  main.classList.remove("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'mini-sidebar');
	  } else {
		  mini3.classList.add("selected");
		  right3.classList.add("d-block");
		  right3.classList.add("simplebar-scrollable-y");
		   side.classList.remove("close");
		  main.classList.add("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'full');
	  }
  });
  
  mini4.addEventListener("click", ()=> {
	  mini1.classList.remove("selected");
	  mini3.classList.remove("selected");
	  mini5.classList.remove("selected");
	  mini6.classList.remove("selected");
	  
	  right1.classList.remove("d-block");
	  right3.classList.remove("simplebar-scrollable-y");
	  right3.classList.remove("d-block");
	  right5.classList.remove("simplebar-scrollable-y");
	  right5.classList.remove("d-block");
	  right6.classList.remove("simplebar-scrollable-y");
	  right6.classList.remove("d-block");
	  
	  if(mini4.classList.contains("selected")) {
		  mini4.classList.remove("selected");
		  right4.classList.remove("simplebar-scrollable-y");
		  right4.classList.remove("d-block");
		  side.classList.add("close");
		  main.classList.remove("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'mini-sidebar');
	  } else {
		  mini4.classList.add("selected");
		  right4.classList.add("d-block");
		  right4.classList.add("simplebar-scrollable-y");
		   side.classList.remove("close");
		  main.classList.add("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'full');
	  }
  });
  
  mini5.addEventListener("click", ()=> {
	  mini1.classList.remove("selected");
	  mini3.classList.remove("selected");
	  mini4.classList.remove("selected");
	  mini6.classList.remove("selected");
	  
	  right1.classList.remove("d-block");
	  right3.classList.remove("simplebar-scrollable-y");
	  right3.classList.remove("d-block");
	  right4.classList.remove("simplebar-scrollable-y");
	  right4.classList.remove("d-block");
	  right6.classList.remove("simplebar-scrollable-y");
	  right6.classList.remove("d-block");

	  if(mini5.classList.contains("selected")) {
		  mini5.classList.remove("selected");
		  right5.classList.remove("simplebar-scrollable-y");
		  right5.classList.remove("d-block");
		  side.classList.add("close");
		  main.classList.remove("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'mini-sidebar');
	  } else {
		  mini5.classList.add("selected");
		  right5.classList.add("d-block");
		  right5.classList.add("simplebar-scrollable-y");
		   side.classList.remove("close");
		  main.classList.add("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'full');
	  }
  });
  
  mini6.addEventListener("click", ()=> {
	  mini1.classList.remove("selected");
	  mini3.classList.remove("selected");
	  mini4.classList.remove("selected");
	  mini5.classList.remove("selected");
	  
	  right1.classList.remove("d-block");
	  right3.classList.remove("simplebar-scrollable-y");
	  right3.classList.remove("d-block");
	  right4.classList.remove("simplebar-scrollable-y");
	  right4.classList.remove("d-block");
	  right5.classList.remove("simplebar-scrollable-y");
	  right5.classList.remove("d-block");

	  if(mini6.classList.contains("selected")) {
		  mini6.classList.remove("selected");
		  right6.classList.remove("simplebar-scrollable-y");
		  right6.classList.remove("d-block");
		  side.classList.add("close");
		  main.classList.remove("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'mini-sidebar');
	  } else {
		  mini6.classList.add("selected");
		  right6.classList.add("d-block");
		  right6.classList.add("simplebar-scrollable-y");
		   side.classList.remove("close");
		  main.classList.add("show-sidebar");
		  document.body.setAttribute('data-sidebartype', 'full');
	  }
  });
  
  
  let first = document.getElementById("first-rep");
  let repdrop1 = document.getElementById("rep-drop1");

  first.addEventListener("click", ()=>{
	  if(first.classList.contains("active")) {
		  first.classList.remove("active");
		  repdrop1.classList.remove("in");
		  
	  } else {
		first.classList.add("active");
		repdrop1.classList.add("in");  
		  
	  }
	  
  });
  
  let candi = document.getElementById("candi-rep");
  let repdrop2 = document.getElementById("rep-drop2");

  candi.addEventListener("click", ()=>{
	  if(candi.classList.contains("active")) {
		  candi.classList.remove("active");
		  repdrop2.classList.remove("in");
		  
	  } else {
		candi.classList.add("active");
		repdrop2.classList.add("in");  
		  
	  }
	  
  });
  
  let risk = document.getElementById("risk-rep");
  let repdrop3 = document.getElementById("rep-drop3");

  risk.addEventListener("click", ()=>{
	  if(risk.classList.contains("active")) {
		  risk.classList.remove("active");
		  repdrop3.classList.remove("in");
		  
	  } else {
		risk.classList.add("active");
		repdrop3.classList.add("in");  
		  
	  }
	  
  });
  
  let flood = document.getElementById("flood-rep");
  let repdrop4 = document.getElementById("rep-drop4");

  flood.addEventListener("click", ()=>{
	  if(flood.classList.contains("active")) {
		  flood.classList.remove("active");
		  repdrop4.classList.remove("in");
		  
	  } else {
		flood.classList.add("active");
		repdrop4.classList.add("in");  
		  
	  }
	  
  });
  
  let status = document.getElementById("status-rep");
  let repdrop5 = document.getElementById("rep-drop5");

  status.addEventListener("click", ()=>{
	  if(status.classList.contains("active")) {
		  status.classList.remove("active");
		  repdrop5.classList.remove("in");
		  
	  } else {
		status.classList.add("active");
		repdrop5.classList.add("in");  
		  
	  }
	  
  });
  
  let plan = document.getElementById("plan-rep");
  let repdrop6 = document.getElementById("rep-drop6");

  plan.addEventListener("click", ()=>{
	  if(plan.classList.contains("active")) {
		  plan.classList.remove("active");
		  repdrop6.classList.remove("in");
		  
	  } else {
		plan.classList.add("active");
		repdrop6.classList.add("in");  
		  
	  }
	  
  });
  
  let reg = document.getElementById("reg");
  let regdrop1 = document.getElementById("reg-drop1");

  reg.addEventListener("click", ()=>{
	  if(reg.classList.contains("active")) {
		  reg.classList.remove("active");
		  regdrop1.classList.remove("in");
		  
	  } else {
		reg.classList.add("active");
		regdrop1.classList.add("in");  
		  
	  }
	  
  });