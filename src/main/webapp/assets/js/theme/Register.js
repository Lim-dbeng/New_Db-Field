/**
 * 
 */
var check1 = $("input[id=flexCheckDefault1]");
var check2 = $("input[id=flexCheckDefault2]");
var check3 = $("input[id=flexCheckDefault3]");
var check4 = $("input[id=flexCheckDefault4]");
var check5 = $("input[id=flexCheckDefault5]");
 
$("#flexCheckDefault").click(function(){
	if($(this).is(":checked")){
		check1.prop("checked", true);
		check2.prop("checked", true);
		check3.prop("checked", true);
		check4.prop("checked", true);
		check5.prop("checked", true);
	} else {
		check1.prop("checked", false);
		check2.prop("checked", false);
		check3.prop("checked", false);
		check4.prop("checked", false);
		check5.prop("checked", false);
	}
});