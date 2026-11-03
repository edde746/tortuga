import SwiftUI

/// GitHub's mark, the Invertocat: the one path of `SVG/GitHub_Invertocat_White.svg` in GitHub's logo files
/// (`GitHub_Logos.zip` from https://github.com/logos, the 2025 mark), in the file's 98 × 96 units, drawn once into a
/// template image, which takes the label's colour as the Apple logo's symbol does: white on the sign-in buttons, black on
/// a primary one, dark on Apple TV's focused platter (GitHub's rules: the mark in one colour, unaltered). An image, not a
/// shape: a shape filled with the foreground style drew at about a fifth of the label's white on the Mac's and Apple
/// TV's glass buttons (17 Oct). It fills the largest rectangle of its proportions that fits, centred.
struct GitHubLogo: View {
    var body: some View {
        Image(decorative: Self.template, scale: Self.pixelsPerUnit)
            .renderingMode(.template)
            .resizable()
            .interpolation(.high)
            .aspectRatio(contentMode: .fit)
    }

    /// The file's view box.
    private static let size = CGSize(width: 98, height: 96)

    /// The image's pixels per unit: about twice the largest mark shown (a 30 pt symbol at 3x).
    private static let pixelsPerUnit: CGFloat = 2

    /// The mark, opaque on clear; the file's y axis points down, CoreGraphics' up.
    private static let template: CGImage = {
        let width = Int(size.width * pixelsPerUnit), height = Int(size.height * pixelsPerUnit)
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              )
        else { fatalError("GitHubLogo: no bitmap context") }
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: pixelsPerUnit, y: -pixelsPerUnit)
        context.addPath(mark.cgPath)
        context.fillPath()
        guard let image = context.makeImage() else { fatalError("GitHubLogo: no image") }
        return image
    }()

    private static let mark = Path(svg:
        "M41.4395 69.3848C28.8066 67.8535 19.9062 58.7617 19.9062 46.9902C19.9062 42.2051 21.6289 37.0371 24.5 " +
            "33.5918C23.2559 30.4336 23.4473 23.7344 24.8828 20.959C28.7109 20.4805 33.8789 22.4902 36.9414 " +
            "25.2656C40.5781 24.1172 44.4062 23.543 49.0957 23.543C53.7852 23.543 57.6133 24.1172 61.0586 " +
            "25.1699C64.0254 22.4902 69.2891 20.4805 73.1172 20.959C74.457 23.543 74.6484 30.2422 73.4043 " +
            "33.4961C76.4668 37.1328 78.0937 42.0137 78.0937 46.9902C78.0937 58.7617 69.1934 67.6621 56.3691 " +
            "69.2891C59.623 71.3945 61.8242 75.9883 61.8242 81.252L61.8242 91.2051C61.8242 94.0762 64.2168 95.7031 " +
            "67.0879 94.5547C84.4102 87.9512 98 70.6289 98 49.1914C98 22.1074 75.9883 6.69539e-07 48.9043 " +
            "4.309e-07C21.8203 1.92261e-07 -1.9479e-07 22.1074 -4.3343e-07 49.1914C-6.20631e-07 70.4375 13.4941 " +
            "88.0469 31.6777 94.6504C34.2617 95.6074 36.75 93.8848 36.75 91.3008L36.75 83.6445C35.4102 84.2188 " +
            "33.6875 84.6016 32.1562 84.6016C25.8398 84.6016 22.1074 81.1563 19.4277 74.7441C18.375 72.1602 " +
            "17.2266 70.6289 15.0254 70.3418C13.877 70.2461 13.4941 69.7676 13.4941 69.1934C13.4941 68.0449 " +
            "15.4082 67.1836 17.3223 67.1836C20.0977 67.1836 22.4902 68.9063 24.9785 72.4473C26.8926 75.2227 " +
            "28.9023 76.4668 31.2949 76.4668C33.6875 76.4668 35.2187 75.6055 37.4199 73.4043C39.0469 71.7773 " +
            "40.291 70.3418 41.4395 69.3848Z"
    )
}
