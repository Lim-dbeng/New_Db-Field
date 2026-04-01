(function ($) {
    const bodyEl = $('body');
    const fullPhotoCarouselEl = $(".fullscreen-backdrop");
    $("#photoCarousel > .carousel-inner").on("click", function (e) {
        const {current: currentIndex} = e.target.dataset;
        $("#fullPhotoCarousel").carousel(Number(currentIndex));
        fullPhotoCarouselEl.css("display", "block");
        bodyEl.css('overflow-y', 'hidden');
    });
    $("#fullscreenCloseBtn").on("click", function () {
        fullPhotoCarouselEl.css("display", "none");
        bodyEl.css('overflow-y', 'scroll');
    });
})(jQuery);