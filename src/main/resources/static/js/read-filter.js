$(document).ready(function () {
    const table = $('#personTable').DataTable({
        columnDefs: [
            { targets: [3, 4, 6, 7], visible: false } // Hide Email (index 3) and SID (index 6) by default
        ]
    });

    // Toggle column visibility and update button styles
    $('.toggle-column').on('click', function () {
        const column = table.column($(this).attr('data-column'));
        const isVisible = column.visible();
        column.visible(!isVisible);

        // Update button styles
        $(this).toggleClass('active', !isVisible);
        $(this).toggleClass('inactive', isVisible);
    });
});